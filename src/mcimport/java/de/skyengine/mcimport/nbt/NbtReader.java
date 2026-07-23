package de.skyengine.mcimport.nbt;

import java.io.BufferedInputStream;
import java.io.DataInput;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PushbackInputStream;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

/**
 * Binärer NBT-Leser (alle 13 Tag-Typen) → typisierter AST ({@link NbtTag}). Kein Writer —
 * der Importer liest nur. Strikt validierend: unbekannte Tag-Typen und absurde Längen
 * werfen {@link IOException}, nichts wird still übersprungen oder korrigiert.
 */
public final class NbtReader {

    private static final byte TAG_END = 0;
    private static final byte TAG_BYTE = 1;
    private static final byte TAG_SHORT = 2;
    private static final byte TAG_INT = 3;
    private static final byte TAG_LONG = 4;
    private static final byte TAG_FLOAT = 5;
    private static final byte TAG_DOUBLE = 6;
    private static final byte TAG_BYTE_ARRAY = 7;
    private static final byte TAG_STRING = 8;
    private static final byte TAG_LIST = 9;
    private static final byte TAG_COMPOUND = 10;
    private static final byte TAG_INT_ARRAY = 11;
    private static final byte TAG_LONG_ARRAY = 12;

    /* Sanity-Deckel gegen korrupte Längenfelder (Arrays/Listen). */
    private static final int MAX_LENGTH = 1 << 26;

    /** Liest ein NBT-Dokument: Root muss TAG_Compound sein (Root-Name wird verworfen). */
    public static NbtCompound read(DataInput in) throws IOException {
        byte rootType = in.readByte();
        if (rootType != TAG_COMPOUND) {
            throw new IOException("NBT-Root ist kein Compound (Typ " + rootType + ")");
        }
        in.readUTF(); // Root-Name (bei Vanilla leer)
        return readCompound(in);
    }

    /**
     * Wie {@link #read}, erkennt die Kompression automatisch: GZIP (Magic {@code 1f 8b},
     * z.B. level.dat), Zlib ({@code 78 ..}) oder unkomprimiert. Für Streams, deren
     * Kompression nicht aus dem Container-Format bekannt ist.
     */
    public static NbtCompound readAuto(InputStream in) throws IOException {
        PushbackInputStream pushback = new PushbackInputStream(in, 2);
        int b0 = pushback.read();
        int b1 = pushback.read();
        if (b1 != -1) pushback.unread(b1);
        if (b0 != -1) pushback.unread(b0);

        InputStream stream;
        if (b0 == 0x1f && b1 == 0x8b) {
            stream = new GZIPInputStream(pushback);
        } else if (b0 == 0x78) {
            stream = new InflaterInputStream(pushback);
        } else {
            stream = pushback;
        }
        return read(new DataInputStream(new BufferedInputStream(stream)));
    }

    private static NbtCompound readCompound(DataInput in) throws IOException {
        NbtCompound compound = new NbtCompound();
        while (true) {
            byte type = in.readByte();
            if (type == TAG_END) return compound;
            String name = in.readUTF();
            compound.put(name, readPayload(in, type));
        }
    }

    private static NbtTag readPayload(DataInput in, byte type) throws IOException {
        return switch (type) {
            case TAG_BYTE -> new NbtTag.NbtByte(in.readByte());
            case TAG_SHORT -> new NbtTag.NbtShort(in.readShort());
            case TAG_INT -> new NbtTag.NbtInt(in.readInt());
            case TAG_LONG -> new NbtTag.NbtLong(in.readLong());
            case TAG_FLOAT -> new NbtTag.NbtFloat(in.readFloat());
            case TAG_DOUBLE -> new NbtTag.NbtDouble(in.readDouble());
            case TAG_STRING -> new NbtTag.NbtString(in.readUTF());
            case TAG_BYTE_ARRAY -> {
                byte[] value = new byte[checkLength(in.readInt(), "ByteArray")];
                in.readFully(value);
                yield new NbtTag.NbtByteArray(value);
            }
            case TAG_INT_ARRAY -> {
                int[] value = new int[checkLength(in.readInt(), "IntArray")];
                for (int i = 0; i < value.length; i++) value[i] = in.readInt();
                yield new NbtTag.NbtIntArray(value);
            }
            case TAG_LONG_ARRAY -> {
                long[] value = new long[checkLength(in.readInt(), "LongArray")];
                for (int i = 0; i < value.length; i++) value[i] = in.readLong();
                yield new NbtTag.NbtLongArray(value);
            }
            case TAG_LIST -> {
                byte elementType = in.readByte();
                int length = checkLength(in.readInt(), "List");
                if (length > 0 && elementType == TAG_END) {
                    throw new IOException("NBT-Liste mit " + length + " Elementen vom Typ End");
                }
                NbtList list = new NbtList(elementType);
                for (int i = 0; i < length; i++) list.add(readPayload(in, elementType));
                yield list;
            }
            case TAG_COMPOUND -> readCompound(in);
            default -> throw new IOException("Unbekannter NBT-Tag-Typ " + type);
        };
    }

    private static int checkLength(int length, String what) throws IOException {
        if (length < 0 || length > MAX_LENGTH) {
            throw new IOException("Ungültige NBT-" + what + "-Länge " + length);
        }
        return length;
    }

    private NbtReader() {}
}
