package su.plo.voice.client.audio.source

import kotlinx.coroutines.*
import kotlinx.coroutines.future.future
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.minecraft.client.Minecraft
import net.minecraft.client.player.LocalPlayer
import net.minecraft.world.phys.Vec3
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import org.lwjgl.openal.AL10
import su.plo.config.entry.BooleanConfigEntry
import su.plo.config.entry.DoubleConfigEntry
import su.plo.voice.api.audio.codec.AudioDecoder
import su.plo.voice.api.audio.codec.CodecException
import su.plo.voice.api.client.PlasmoVoiceClient
import su.plo.voice.api.client.audio.device.AlAudioDevice
import su.plo.voice.api.client.audio.device.DeviceType
import su.plo.voice.api.client.audio.device.source.AlSource
import su.plo.voice.api.client.audio.device.source.SourceGroup
import su.plo.voice.api.client.audio.source.ClientAudioSource
import su.plo.voice.api.client.connection.ServerInfo.VoiceInfo
import su.plo.voice.api.client.event.audio.device.source.AlSourceClosedEvent
import su.plo.voice.api.client.event.audio.device.source.AlStreamSourceStoppedEvent
import su.plo.voice.api.client.event.audio.source.AudioSourceClosedEvent
import su.plo.voice.api.client.event.audio.source.AudioSourceInitializedEvent
import su.plo.voice.api.encryption.Encryption
import su.plo.voice.api.encryption.EncryptionException
import su.plo.voice.api.event.EventPriority
import su.plo.voice.api.event.EventSubscribe
import su.plo.voice.api.util.AudioUtil
import su.plo.voice.api.util.Params
import su.plo.voice.client.audio.SoundOcclusion
import su.plo.voice.client.audio.codec.AudioDecoderPlc
import su.plo.voice.client.config.ClientConfig
import su.plo.voice.proto.data.audio.codec.CodecInfo
import su.plo.voice.proto.data.audio.source.SourceInfo
import su.plo.voice.proto.packets.tcp.clientbound.SourceAudioEndPacket
import su.plo.voice.proto.packets.udp.clientbound.SourceAudioPacket
import java.util.*
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sqrt

abstract class BaseClientAudioSource<T> constructor(
    protected val voiceClient: PlasmoVoiceClient,
    protected val config: ClientConfig,
    final override var sourceInfo: T
) : ClientAudioSource<T> where T : SourceInfo {

    private val playerPosition = FloatArray(3)
    private val position = FloatArray(3)
    private val lookAngle = FloatArray(3)


    override var sourceGroup: SourceGroup = runBlocking { createSourceGroup(sourceInfo) }

    private var lineVolume: DoubleConfigEntry
    private var lineMute: BooleanConfigEntry
    open var sourceVolume: DoubleConfigEntry = config.voice
        .volumes
        .getVolume("source_${sourceInfo.id}")

    private var encryption: Encryption? = null
    private var decoder: AudioDecoder? = null

    private var endRequest: Job? = null

    override var closeTimeoutMs: Long = 500

    private var lastSequenceNumbers: MutableMap<UUID, Long> = HashMap()
    private var lastActivation = 0L
    private var lastOcclusion = -1.0

    private val closed = AtomicBoolean(false)
    private val resetted = AtomicBoolean(false)
    private val activated = AtomicBoolean(false)
    private val canHear = AtomicBoolean(false)

    private val mutex = Mutex()

    init {
        val serverInfo = voiceClient.serverInfo
            .orElseThrow { IllegalStateException("Not connected") }
        val voiceInfo = serverInfo.voiceInfo

        // initialize decoder
        sourceInfo.decoderInfo?.let {
            decoder = createDecoder(voiceInfo, it)
        }

        // initialize encryption
        serverInfo.encryption.ifPresent {
            encryption = it
        }

        // initialize volumes
        lineVolume = getLineVolume(sourceInfo)
        lineMute = getLineMute(sourceInfo)
        LOGGER.info(
            "Source {} initialized in {}",
            sourceInfo,
            if (isStereo(sourceInfo)) "stereo" else "mono"
        )
    }

    override fun update(sourceInfo: T): Unit = runBlocking {
        mutex.withLock {
            val serverInfo = voiceClient.serverInfo
                .orElseThrow { IllegalStateException("Not connected") }

            val voiceInfo = serverInfo.voiceInfo
            val stereoChanged = isStereo(this@BaseClientAudioSource.sourceInfo) != isStereo(sourceInfo)

            // initialize sources
            if (stereoChanged) {
                val oldSourceGroup = sourceGroup
                sourceGroup = createSourceGroup(sourceInfo)
                oldSourceGroup.clear()

                LOGGER.info(
                    "Update device sources for {} in {}",
                    sourceInfo,
                    if (isStereo(sourceInfo)) "stereo" else "mono"
                )
            }

            // initialize decoder
            if (sourceInfo.isStereo != this@BaseClientAudioSource.sourceInfo.isStereo) {
                decoder?.close()

                sourceInfo.decoderInfo?.let {
                    decoder = createDecoder(voiceInfo, it)
                }
                LOGGER.info("Update decoder for {}", sourceInfo)
            }

            // initialize encryption
            serverInfo.encryption.ifPresent {
                encryption = it
            }

            // initialize volumes
            if (sourceInfo.lineId != this@BaseClientAudioSource.sourceInfo.lineId) {
                lineVolume = getLineVolume(sourceInfo)
                lineMute = getLineMute(sourceInfo)
                LOGGER.info("Update source line for {}", sourceInfo)
            }

            this@BaseClientAudioSource.sourceInfo = sourceInfo

            voiceClient.eventBus.call(AudioSourceInitializedEvent(this@BaseClientAudioSource))
        }
    }

    override fun process(packet: SourceAudioPacket) {
        if (isClosed() || lineMute.value()) return

        SCOPE.launch { processAudioPacket(packet) }
    }

    override fun process(packet: SourceAudioEndPacket) {
        if (isClosed() || lineMute.value()) return

        SCOPE.launch { processAudioEndPacket(packet) }
        endRequest?.cancel()
        endRequest = SCOPE.launch {
            try {
                delay(100L)
                reset()
            } catch (_: CancellationException) {
            }
        }
    }

    override suspend fun close() = mutex.withLock{
        activated.set(false)
        canHear.set(false)
        closed.set(true)

        decoder?.close()
        sourceGroup.clear()

        voiceClient.eventBus.call(AudioSourceClosedEvent(this@BaseClientAudioSource))
        LOGGER.info("Source {} closed", sourceInfo)
    }

    override fun closeAsync(): CompletableFuture<Void?> =
        SCOPE.future {
            close()
            null
        }

    override fun isActivated(): Boolean {
        if (activated.get()) {
            if (closeTimeoutMs > 0L && System.currentTimeMillis() - lastActivation > closeTimeoutMs) {
                LOGGER.warn("Voice end packet was not received. Resetting audio source")
                resetAsync()
                return false
            }
            return true
        }

        return false
    }

    override fun isClosed(): Boolean {
        return closed.get()
    }

    override fun canHear(): Boolean {
        return canHear.get()
    }

    @EventSubscribe(priority = EventPriority.LOWEST)
    fun onSourceClosed(event: AlSourceClosedEvent) {
        if (closed.get() || !sourceGroup.sources.contains(event.source)) return
        closeAsync()
    }

    @EventSubscribe(priority = EventPriority.LOWEST)
    fun onSourceStopped(event: AlStreamSourceStoppedEvent) {
        if (closed.get() || !sourceGroup.sources.contains(event.source) || closeTimeoutMs == 0L) return
        resetAsync()
    }

    private suspend fun processAudioPacket(packet: SourceAudioPacket) = mutex.withLock {
        if (packet.sourceState != sourceInfo.state) {
            LOGGER.warn("Drop packet with bad source state {}", sourceInfo)
            return
        }

        val lastSequenceNumber = lastSequenceNumbers[sourceInfo.lineId] ?: -1L

        // drop packet with bad order
        if (lastSequenceNumber >= 0 && packet.sequenceNumber <= lastSequenceNumber) {
            if (lastSequenceNumber - packet.sequenceNumber < 10L) {
                LOGGER.info("Drop packet with bad order")
                return
            }
            lastSequenceNumbers.remove(sourceInfo.lineId)
        }

        // todo: waytoodank
        endRequest?.let {
            it.cancel()
            endRequest = null
        }

        // update source positions
        try {
            getPlayerPosition(playerPosition)
            getPosition(position)
            getLookAngle(lookAngle)
        } catch (e: IllegalStateException) {
            close()
            return
        }

        val distance = packet.distance.toInt()

        val sourceDistance = getSourceDistance(position)
        val minSourceDistance = sourceDistance.coerceAtMost(distance)
        val distanceGain = 1f - minSourceDistance.toFloat() / distance.toFloat()

        // calculate volume
        var volume = config.voice.volume.value() * sourceVolume.value() * lineVolume.value()
        if (shouldCalculateOcclusion()) {
            // todo: disable occlusion via client addon?
            var occlusion: Double = calculateOcclusion(position)
            if (lastOcclusion >= 0) {
                lastOcclusion = if (occlusion > lastOcclusion) {
                    (lastOcclusion + 0.05).coerceAtLeast(0.0)
                } else {
                    (lastOcclusion - 0.05).coerceAtLeast(occlusion)
                }
                occlusion = lastOcclusion
            }

            volume *= (1.0 - occlusion)
            if (lastOcclusion == -1.0) {
                lastOcclusion = occlusion
            }
        }

        // update source volume & distance
        if (isStereoOrPanningDisabled(sourceInfo) && distance > 0) {
            updateSource(volume.toFloat() * distanceGain, packet.distance.toInt())
        } else {
            updateSource(volume.toFloat(), packet.distance.toInt())
        }

        // after updating the source, source can be closed by reloading the device,
        // so we need to make sure that source is not closed rn
        if (closed.get()) return

        // packet compensation
        if (lastSequenceNumber >= 0) {
            val packetsToCompensate = (packet.sequenceNumber - (lastSequenceNumber + 1)).toInt()
            if (packetsToCompensate <= 4) {
                LOGGER.debug("Compensate {} packets", packetsToCompensate)
                for (i in 0 until packetsToCompensate) {
                    if (decoder != null && decoder is AudioDecoderPlc && !sourceInfo.isStereo) {
                        try {
                            write((decoder as AudioDecoderPlc).decodePLC())
                        } catch (e: CodecException) {
                            LOGGER.warn("Failed to decode source audio", e)
                            return
                        }
                    } else {
                        write(ShortArray(0))
                    }
                }
            }
        }

        // decrypt & decode samples
        try {
            val decrypted = encryption?.decrypt(packet.data) ?: packet.data
            val decoded = decoder?.decode(decrypted) ?: AudioUtil.bytesToShorts(decrypted)

            if (sourceInfo.isStereo && config.advanced.stereoSourcesToMono.value()) {
                write(AudioUtil.convertToMonoShorts(decoded))
            } else {
                write(decoded)
            }
        } catch (e: EncryptionException) {
            LOGGER.warn("Failed to decrypt source audio", e)
        } catch (e: CodecException) {
            LOGGER.warn("Failed to decode source audio", e)
        }

        lastSequenceNumbers[sourceInfo.lineId] = packet.sequenceNumber
        lastActivation = System.currentTimeMillis()

        if (distance > 0) canHear.set(sourceDistance <= distance)
        activated.set(true)
        resetted.set(false)
    }

    private suspend fun processAudioEndPacket(packet: SourceAudioEndPacket) = mutex.withLock {
        if (!activated.get()) return
        lastSequenceNumbers[sourceInfo.lineId] = packet.sequenceNumber
    }

    private suspend fun reset() = mutex.withLock {
        if (!resetted.compareAndSet(false, true)) return
        if (decoder != null) decoder!!.reset()
        activated.set(false)
        canHear.set(false)
    }

    private fun resetAsync() =
        SCOPE.future {
            reset()
            null
        }

    protected open fun getPlayerPosition(position: FloatArray): FloatArray {
        // todo: find out why modApi remapping not working in kotlin
        val player: LocalPlayer = Minecraft.getInstance().player ?: return position

        position[0] = player.x.toFloat()
        position[1] = (player.y + player.eyeHeight).toFloat()
        position[2] = player.z.toFloat()
        return position
    }

    protected abstract fun getPosition(position: FloatArray): FloatArray

    protected abstract fun getLookAngle(lookAngle: FloatArray): FloatArray

    protected open fun shouldCalculateOcclusion(): Boolean {
        return config.voice.soundOcclusion.value()
    }

    private fun calculateOcclusion(position: FloatArray): Double {
        val player: LocalPlayer = Minecraft.getInstance().player ?: return 0.0
        return SoundOcclusion.getOccludedPercent(
            player.level,
            Vec3(position[0].toDouble(), position[1].toDouble(), position[2].toDouble()),
            player.eyePosition
        )
    }

    private fun write(samples: ShortArray) {
        for (source in sourceGroup.sources) {
            source.write(
                AudioUtil.shortsToBytes(
                    source.device.processFilters(samples)
                )
            )
        }
    }

    private suspend fun updateSource(volume: Float, maxDistance: Int) {
        for (source in sourceGroup.sources) {
            if (source !is AlSource) continue
            val device = source.device as AlAudioDevice

            device.runInContext {
                source.volume = volume

                if (isPanningDisabled()) return@runInContext

                source.setFloatArray(0x1004, position) // AL_POSITION
                source.setFloat(0x1020, 0f) // AL_REFERENCE_DISTANCE
                if (maxDistance > 0) {
                    source.setFloat(0x1023, maxDistance.toFloat()) // AL_MAX_DISTANCE
                }
                if (config.voice.directionalSources.value()) {
                    source.setFloatArray(0x1005, lookAngle) // AL_DIRECTION
                    source.setFloat(0x1022, 0f) // AL_CONE_OUTER_GAIN
                    source.setFloat(
                        0x1001,  // AL_CONE_INNER_ANGLE
                        90f
                    )
                    source.setFloat(
                        0x1002,  // AL_CONE_OUTER_ANGLE
                        180f
                    )
                } else {
                    source.setFloatArray(
                        0x1005,
                        ZERO_VECTOR
                    ) // AL_DIRECTION
                }
            }
        }
    }

    private suspend fun createSourceGroup(sourceInfo: T): SourceGroup {
        return voiceClient.deviceManager.createSourceGroup(DeviceType.OUTPUT).also {
            it.create(isStereo(sourceInfo), Params.EMPTY)

            for (source in it.sources) {
                if (source !is AlSource) continue

                val device = source.device as AlAudioDevice
                device.runInContext {
                    source.setFloat(0x100E, 4f) // AL_MAX_GAIN
                    if (!isPanningDisabled())
                        source.setInt(0xD000, 0xD003) // AL_DISTANCE_MODEL // AL_LINEAR_DISTANCE
                    else {
                        source.setInt(AL10.AL_DISTANCE_MODEL, AL10.AL_NONE);
                        source.setInt(
                            0x202,  // AL_SOURCE_RELATIVE
                            1
                        )
                    }
                    source.play()
                }
            }
        }
    }

    private fun createDecoder(voiceInfo: VoiceInfo, decoderInfo: CodecInfo): AudioDecoder {
        return voiceClient.codecManager.createDecoder(
            decoderInfo,
            voiceInfo.captureInfo.sampleRate,
            sourceInfo.isStereo,
            voiceInfo.bufferSize,
            voiceInfo.captureInfo.mtuSize
        )
    }

    private fun getLineVolume(sourceInfo: T): DoubleConfigEntry {
        val sourceLine = voiceClient.sourceLineManager.getLineById(sourceInfo.lineId)
            .orElseThrow { IllegalStateException("Source line not found") }

        return config.voice
            .volumes
            .getVolume(sourceLine.name)
    }

    private fun getLineMute(sourceInfo: T): BooleanConfigEntry {
        val sourceLine = voiceClient.sourceLineManager.getLineById(sourceInfo.lineId)
            .orElseThrow { IllegalStateException("Source line not found") }

        return config.voice
            .volumes
            .getMute(sourceLine.name)
    }

    protected open fun isPanningDisabled(): Boolean =
        !config.advanced.panning.value()

    private fun isStereoOrPanningDisabled(sourceInfo: SourceInfo): Boolean =
        isPanningDisabled() || isStereo(sourceInfo)

    private fun isStereo(sourceInfo: SourceInfo): Boolean {
        return sourceInfo.isStereo && !config.advanced.stereoSourcesToMono.value()
    }

    private fun getSourceDistance(position: FloatArray): Int {
        val xDiff = (playerPosition[0] - position[0]).toDouble()
        val yDiff = (playerPosition[1] - position[1]).toDouble()
        val zDiff = (playerPosition[2] - position[2]).toDouble()
        return sqrt(xDiff * xDiff + yDiff * yDiff + zDiff * zDiff).toInt()
    }

    companion object {
        private val ZERO_VECTOR = floatArrayOf(0f, 0f, 0f)
        private val LOGGER: Logger = LogManager.getLogger()

        private val SCOPE = CoroutineScope(Executors.newSingleThreadExecutor().asCoroutineDispatcher())
    }
}
