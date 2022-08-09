package su.plo.voice.proto.packets;

import com.google.common.io.ByteArrayDataInput;
import com.google.common.io.ByteArrayDataOutput;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class PacketUtil {

    public static void writeUUID(ByteArrayDataOutput out, UUID uuid) {
        out.writeLong(uuid.getMostSignificantBits());
        out.writeLong(uuid.getLeastSignificantBits());
    }

    public static UUID readUUID(ByteArrayDataInput in) {
        return new UUID(in.readLong(), in.readLong());
    }

    public static void writeIntList(ByteArrayDataOutput out, List<Integer> list) {
        out.writeInt(list.size());
        for (int i : list) out.writeInt(i);
    }

    public static List<Integer> readIntList(ByteArrayDataInput in) {
        int size = in.readInt();
        List<Integer> list = new ArrayList<>(size);

        for (int i = 0; i < size; i++) list.add(in.readInt());

        return list;
    }

    private PacketUtil() {
    }
}