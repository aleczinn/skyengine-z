package de.skyengine.game.world.structure;

import de.skyengine.game.world.block.entity.DataTag;
import de.skyengine.mcimport.nbt.NbtCompound;
import de.skyengine.mcimport.nbt.NbtTag;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Map;

/** Verlustfreier Codec zwischen Engine-DataTags und dem NBT-Payload nativer Structures. */
final class DataTagNbtCodec {

    static NbtCompound toNbt(DataTag source) {
        NbtCompound result = new NbtCompound();
        source.raw().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry ->
                result.put(entry.getKey(), toNbtValue(entry.getValue())));
        return result;
    }

    static DataTag fromNbt(NbtCompound source) throws IOException {
        return fromNbt(source, 0);
    }

    private static DataTag fromNbt(NbtCompound source, int depth) throws IOException {
        if (depth > 64) throw new IOException("BlockEntity-Daten sind tiefer als 64 Ebenen verschachtelt");
        if (source.size() > 4096) throw new IOException("BlockEntity-DataTag hat mehr als 4096 Eintraege");
        DataTag result = new DataTag();
        for (Map.Entry<String, NbtTag> entry : source.entries().entrySet()) {
            String key = entry.getKey();
            switch (entry.getValue()) {
                case NbtTag.NbtByte value -> result.putBoolean(key, value.value() != 0);
                case NbtTag.NbtShort value -> result.putInt(key, value.value());
                case NbtTag.NbtInt value -> result.putInt(key, value.value());
                case NbtTag.NbtLong value -> result.putLong(key, value.value());
                case NbtTag.NbtFloat value -> result.putDouble(key, value.value());
                case NbtTag.NbtDouble value -> result.putDouble(key, value.value());
                case NbtTag.NbtString value -> result.putString(key, value.value());
                case NbtCompound value -> result.putTag(key, fromNbt(value, depth + 1));
                default -> throw new IOException("Nicht unterstuetzter NBT-Typ in BlockEntity-Daten bei '" + key + "'");
            }
        }
        return result;
    }

    static void updateDigest(MessageDigest digest, DataTag tag) {
        ArrayList<Map.Entry<String, Object>> entries = new ArrayList<>(tag.raw().entrySet());
        entries.sort(Comparator.comparing(Map.Entry::getKey));
        for (Map.Entry<String, Object> entry : entries) {
            digest.update(entry.getKey().getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            updateValue(digest, entry.getValue());
        }
    }

    private static NbtTag toNbtValue(Object value) {
        return switch (value) {
            case Integer v -> new NbtTag.NbtInt(v);
            case Long v -> new NbtTag.NbtLong(v);
            case Double v -> new NbtTag.NbtDouble(v);
            case Boolean v -> new NbtTag.NbtByte((byte) (v ? 1 : 0));
            case String v -> new NbtTag.NbtString(v);
            case DataTag v -> toNbt(v);
            case Number v -> new NbtTag.NbtDouble(v.doubleValue());
            default -> throw new IllegalArgumentException("Nicht serialisierbarer DataTag-Wert: " + value);
        };
    }

    private static void updateValue(MessageDigest digest, Object value) {
        byte type = switch (value) {
            case Integer ignored -> 1; case Long ignored -> 2; case Double ignored -> 3;
            case Boolean ignored -> 4; case String ignored -> 5; case DataTag ignored -> 6;
            case Number ignored -> 3;
            default -> throw new IllegalArgumentException("Nicht serialisierbarer DataTag-Wert: " + value);
        };
        digest.update(type);
        if (value instanceof DataTag nested) updateDigest(digest, nested);
        else digest.update(String.valueOf(value).getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
    }

    private DataTagNbtCodec() {}
}
