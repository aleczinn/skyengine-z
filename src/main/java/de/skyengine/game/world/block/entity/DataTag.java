package de.skyengine.game.world.block.entity;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Schlichter, baumartiger Datencontainer (NBT-artig) für BlockEntity-Persistenz.
 * Map-basiert und damit direkt Gson-serialisierbar. Verschachtelung über {@link #putTag}.
 */
public final class DataTag {

    private final Map<String, Object> values = new LinkedHashMap<>();

    public DataTag putInt(String key, int v) { values.put(key, v); return this; }
    public DataTag putLong(String key, long v) { values.put(key, v); return this; }
    public DataTag putDouble(String key, double v) { values.put(key, v); return this; }
    public DataTag putBoolean(String key, boolean v) { values.put(key, v); return this; }
    public DataTag putString(String key, String v) { values.put(key, v); return this; }
    public DataTag putTag(String key, DataTag v) { values.put(key, v); return this; }

    public int getInt(String key, int def) {
        Object o = values.get(key);
        return o instanceof Number n ? n.intValue() : def;
    }
    public long getLong(String key, long def) {
        Object o = values.get(key);
        return o instanceof Number n ? n.longValue() : def;
    }
    public double getDouble(String key, double def) {
        Object o = values.get(key);
        return o instanceof Number n ? n.doubleValue() : def;
    }
    public boolean getBoolean(String key, boolean def) {
        Object o = values.get(key);
        return o instanceof Boolean b ? b : def;
    }
    public String getString(String key, String def) {
        Object o = values.get(key);
        return o instanceof String s ? s : def;
    }
    public DataTag getTag(String key) {
        Object o = values.get(key);
        return o instanceof DataTag t ? t : null;
    }

    public boolean isEmpty() { return values.isEmpty(); }
    public Map<String, Object> raw() { return values; }

    /** Tiefe Kopie fuer transaktionale Editor-Snapshots. */
    public DataTag copy() {
        DataTag copy = new DataTag();
        for (Map.Entry<String, Object> entry : this.values.entrySet()) {
            Object value = entry.getValue();
            copy.values.put(entry.getKey(), value instanceof DataTag nested ? nested.copy() : value);
        }
        return copy;
    }

    @Override public boolean equals(Object other) {
        return other instanceof DataTag tag && this.values.equals(tag.values);
    }

    @Override public int hashCode() { return this.values.hashCode(); }
}
