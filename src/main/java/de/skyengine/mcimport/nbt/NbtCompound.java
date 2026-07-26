package de.skyengine.mcimport.nbt;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * NBT-Compound mit typisierten Accessorn in zwei Varianten:
 * <ul>
 *   <li>{@code get*} — optional: fehlender Key oder falscher Typ liefert null bzw.
 *       den Default (nie eine Exception),</li>
 *   <li>{@code require*} — Pflichtfeld: fehlend/falscher Typ wirft {@link IOException}
 *       mit Key-Name (Parser-Code liest sich damit ohne null-Kaskaden).</li>
 * </ul>
 * Ganzzahl-Accessoren akzeptieren jeden integralen Tag (Byte/Short/Int/Long) — NBT
 * speichert kleine Zahlen häufig als Byte (z.B. Section-{@code Y}).
 */
public final class NbtCompound implements NbtTag {

    private final Map<String, NbtTag> values = new LinkedHashMap<>();

    void put(String key, NbtTag tag) {
        this.values.put(key, tag);
    }

    public boolean contains(String key) {
        return this.values.containsKey(key);
    }

    public Set<String> keys() {
        return this.values.keySet();
    }

    public int size() {
        return this.values.size();
    }

    /* --- optionale Accessoren --- */

    public NbtCompound getCompound(String key) {
        return this.values.get(key) instanceof NbtCompound c ? c : null;
    }

    public NbtList getList(String key) {
        return this.values.get(key) instanceof NbtList l ? l : null;
    }

    public String getString(String key, String def) {
        return this.values.get(key) instanceof NbtString s ? s.value() : def;
    }

    public int getInt(String key, int def) {
        Long v = integral(this.values.get(key));
        return v != null ? (int) (long) v : def;
    }

    public long getLong(String key, long def) {
        Long v = integral(this.values.get(key));
        return v != null ? v : def;
    }

    public long[] getLongArray(String key) {
        return this.values.get(key) instanceof NbtTag.NbtLongArray a ? a.value() : null;
    }

    public int[] getIntArray(String key) {
        return this.values.get(key) instanceof NbtTag.NbtIntArray a ? a.value() : null;
    }

    public byte[] getByteArray(String key) {
        return this.values.get(key) instanceof NbtTag.NbtByteArray a ? a.value() : null;
    }

    public double getDouble(String key, double def) {
        return switch (this.values.get(key)) {
            case NbtTag.NbtDouble d -> d.value();
            case NbtTag.NbtFloat f -> f.value();
            case NbtTag t when integral(t) != null -> integral(t);
            case null, default -> def;
        };
    }

    /* --- Pflichtfeld-Accessoren --- */

    public NbtCompound requireCompound(String key) throws IOException {
        NbtCompound c = getCompound(key);
        if (c == null) throw missing(key, "Compound");
        return c;
    }

    public NbtList requireList(String key) throws IOException {
        NbtList l = getList(key);
        if (l == null) throw missing(key, "List");
        return l;
    }

    public String requireString(String key) throws IOException {
        String s = getString(key, null);
        if (s == null) throw missing(key, "String");
        return s;
    }

    public int requireInt(String key) throws IOException {
        Long v = integral(this.values.get(key));
        if (v == null) throw missing(key, "Int");
        return (int) (long) v;
    }

    public long[] requireLongArray(String key) throws IOException {
        long[] a = getLongArray(key);
        if (a == null) throw missing(key, "LongArray");
        return a;
    }

    private IOException missing(String key, String expected) {
        NbtTag actual = this.values.get(key);
        return new IOException("NBT-Pflichtfeld '" + key + "' fehlt oder ist kein " + expected
                + (actual != null ? " (ist " + actual.getClass().getSimpleName() + ")" : ""));
    }

    private static Long integral(NbtTag tag) {
        return switch (tag) {
            case NbtTag.NbtByte b -> (long) b.value();
            case NbtTag.NbtShort s -> (long) s.value();
            case NbtTag.NbtInt i -> (long) i.value();
            case NbtTag.NbtLong l -> l.value();
            case null, default -> null;
        };
    }
}
