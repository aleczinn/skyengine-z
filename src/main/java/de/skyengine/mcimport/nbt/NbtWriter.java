package de.skyengine.mcimport.nbt;

import java.io.DataOutput;
import java.io.IOException;
import java.util.Map;

/** Symmetrischer, kleiner NBT-Schreiber fuer native Engine-Werkzeugformate. */
public final class NbtWriter {

    public static void write(DataOutput out, String rootName, NbtCompound root) throws IOException {
        out.writeByte(10);
        out.writeUTF(rootName == null ? "" : rootName);
        writeCompound(out, root);
    }

    private static void writeCompound(DataOutput out, NbtCompound compound) throws IOException {
        for (Map.Entry<String, NbtTag> entry : compound.entries().entrySet()) {
            byte type = typeOf(entry.getValue());
            out.writeByte(type);
            out.writeUTF(entry.getKey());
            writePayload(out, type, entry.getValue());
        }
        out.writeByte(0);
    }

    private static void writePayload(DataOutput out, byte type, NbtTag tag) throws IOException {
        switch (type) {
            case 1 -> out.writeByte(((NbtTag.NbtByte) tag).value());
            case 2 -> out.writeShort(((NbtTag.NbtShort) tag).value());
            case 3 -> out.writeInt(((NbtTag.NbtInt) tag).value());
            case 4 -> out.writeLong(((NbtTag.NbtLong) tag).value());
            case 5 -> out.writeFloat(((NbtTag.NbtFloat) tag).value());
            case 6 -> out.writeDouble(((NbtTag.NbtDouble) tag).value());
            case 7 -> {
                byte[] value = ((NbtTag.NbtByteArray) tag).value();
                out.writeInt(value.length);
                out.write(value);
            }
            case 8 -> out.writeUTF(((NbtTag.NbtString) tag).value());
            case 9 -> {
                NbtList list = (NbtList) tag;
                out.writeByte(list.elementType());
                out.writeInt(list.size());
                for (int i = 0; i < list.size(); i++) writePayload(out, list.elementType(), list.get(i));
            }
            case 10 -> writeCompound(out, (NbtCompound) tag);
            case 11 -> {
                int[] value = ((NbtTag.NbtIntArray) tag).value();
                out.writeInt(value.length);
                for (int element : value) out.writeInt(element);
            }
            case 12 -> {
                long[] value = ((NbtTag.NbtLongArray) tag).value();
                out.writeInt(value.length);
                for (long element : value) out.writeLong(element);
            }
            default -> throw new IOException("Nicht schreibbarer NBT-Typ " + type);
        }
    }

    private static byte typeOf(NbtTag tag) throws IOException {
        return switch (tag) {
            case NbtTag.NbtByte ignored -> 1;
            case NbtTag.NbtShort ignored -> 2;
            case NbtTag.NbtInt ignored -> 3;
            case NbtTag.NbtLong ignored -> 4;
            case NbtTag.NbtFloat ignored -> 5;
            case NbtTag.NbtDouble ignored -> 6;
            case NbtTag.NbtByteArray ignored -> 7;
            case NbtTag.NbtString ignored -> 8;
            case NbtList ignored -> 9;
            case NbtCompound ignored -> 10;
            case NbtTag.NbtIntArray ignored -> 11;
            case NbtTag.NbtLongArray ignored -> 12;
            default -> throw new IOException("Unbekannter NBT-Typ " + tag);
        };
    }

    private NbtWriter() {}
}
