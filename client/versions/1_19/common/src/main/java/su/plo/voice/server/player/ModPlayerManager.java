package su.plo.voice.server.player;

import lombok.AllArgsConstructor;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import su.plo.voice.api.server.PlasmoVoiceServer;
import su.plo.voice.api.server.player.VoicePlayer;

import java.util.Optional;
import java.util.UUID;

@AllArgsConstructor
public final class ModPlayerManager extends BasePlayerManager {

    private final PlasmoVoiceServer voiceServer;
    private final MinecraftServer server;

    @Override
    public Optional<VoicePlayer> getPlayer(UUID playerId) {
        Optional<VoicePlayer> player = super.getPlayer(playerId);
        if (player.isEmpty()) {
            ServerPlayer serverPlayer = server.getPlayerList().getPlayer(playerId);
            if (serverPlayer == null) return Optional.empty();

            VoicePlayer voicePlayer = new ModVoicePlayer(voiceServer, serverPlayer);
            playerById.put(serverPlayer.getUUID(), voicePlayer);
        }

        return player;
    }

    @Override
    public Optional<VoicePlayer> getPlayer(Object player) {
        if (!(player instanceof ServerPlayer serverPlayer))
            throw new IllegalArgumentException("player is not " + ServerPlayer.class);

        VoicePlayer voicePlayer = new ModVoicePlayer(voiceServer, serverPlayer);
        playerById.put(serverPlayer.getUUID(), voicePlayer);

        return Optional.of(voicePlayer);
    }
}