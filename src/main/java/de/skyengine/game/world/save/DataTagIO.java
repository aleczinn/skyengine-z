package de.skyengine.game.world.save;

import de.skyengine.game.world.block.entity.DataTag;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.Map;

/**
 * Binäres Lesen/Schreiben von {@link DataTag}-Bäumen (BlockEntity-/Spieler-Persistenz).
 *
 * <p>Format pro Compound: Folge von Einträgen {@code byte typ, writeUTF(key), wert},
 * abgeschlossen mit {@code TYPE_END}. Das Typ-Byte-Schema lässt Raum für spätere Typen
 * (z.B. Listen, byte[]); ein unbekannter Typ beim Lesen ist ein harter Fehler mit klarer
 * Meldung — nie stilles Überspringen (das würde den restlichen Stream verwürfeln).
 */
public final class DataTagIO {

    private static final int MAX_DEPTH = 64;
    private static final int MAX_ENTRIES_PER_TAG = 4096;

    private static final byte TYPE_END = 0;
    private static final byte TYPE_INT = 1;
    private static final byte TYPE_LONG = 2;
    private static final byte TYPE_DOUBLE = 3;
    private static final byte TYPE_BOOLEAN = 4;
    private static final byte TYPE_STRING = 5;
    private static final byte TYPE_TAG = 6;

    public static void write(DataTag tag, DataOutput out) throws IOException {
        for (Map.Entry<String, Object> entry : tag.raw().entrySet()) {
            Object value = entry.getValue();
            /* GSON-geladene Tags können Number-Untertypen mischen — instanceof Number
               deckt das ab, geschrieben wird immer der engste passende Typ. */
            switch (value) {
                case Integer i -> { out.writeByte(TYPE_INT); out.writeUTF(entry.getKey()); out.writeInt(i); }
                case Long l -> { out.writeByte(TYPE_LONG); out.writeUTF(entry.getKey()); out.writeLong(l); }
                case Double d -> { out.writeByte(TYPE_DOUBLE); out.writeUTF(entry.getKey()); out.writeDouble(d); }
                case Boolean b -> { out.writeByte(TYPE_BOOLEAN); out.writeUTF(entry.getKey()); out.writeBoolean(b); }
                case String s -> { out.writeByte(TYPE_STRING); out.writeUTF(entry.getKey()); out.writeUTF(s); }
                case DataTag t -> { out.writeByte(TYPE_TAG); out.writeUTF(entry.getKey()); write(t, out); }
                case Number n -> { out.writeByte(TYPE_DOUBLE); out.writeUTF(entry.getKey()); out.writeDouble(n.doubleValue()); }
                default -> throw new IOException("DataTag-Wert von '" + entry.getKey()
                        + "' ist nicht serialisierbar: " + value.getClass().getName());
            }
        }
        out.writeByte(TYPE_END);
    }

    public static DataTag read(DataInput in) throws IOException {
        return read(in, 0);
    }

    private static DataTag read(DataInput in, int depth) throws IOException {
        if (depth > MAX_DEPTH) {
            throw new IOException("DataTag-Verschachtelung tiefer als " + MAX_DEPTH);
        }
        DataTag tag = new DataTag();
        int entries = 0;
        while (true) {
            byte type = in.readByte();
            if (type == TYPE_END) return tag;
            if (++entries > MAX_ENTRIES_PER_TAG) {
                throw new IOException("DataTag enthält mehr als " + MAX_ENTRIES_PER_TAG + " Einträge");
            }
            String key = in.readUTF();
            switch (type) {
                case TYPE_INT -> tag.putInt(key, in.readInt());
                case TYPE_LONG -> tag.putLong(key, in.readLong());
                case TYPE_DOUBLE -> tag.putDouble(key, in.readDouble());
                case TYPE_BOOLEAN -> tag.putBoolean(key, in.readBoolean());
                case TYPE_STRING -> tag.putString(key, in.readUTF());
                case TYPE_TAG -> tag.putTag(key, read(in, depth + 1));
                default -> throw new IOException("Unbekannter DataTag-Typ " + type
                        + " bei Key '" + key + "' — Datei aus neuerer Version?");
            }
        }
    }

    private DataTagIO() {}
}
