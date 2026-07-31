package de.skyengine.utils.collect;

import java.util.Arrays;

/**
 * Offene long→Objekt-Map (lineares Sondieren, Pow2-Kapazität, Load-Faktor 0,75) als Ersatz für
 * {@code HashMap<Long, V>} in Frame-/Tick-Hotpaths: kein Key-Boxing, keine Node-Allokationen,
 * Iteration als flacher Array-Scan. NICHT threadsicher — nur für Render-/Tick-Thread-Strukturen.
 *
 * <p>Leer-Erkennung läuft über {@code values[i] == null} (null-Werte sind damit verboten,
 * der Key 0 aber unproblematisch). Entfernen per Backward-Shift (keine Tombstones).</p>
 *
 * <p><b>Iteration:</b> {@link #tableSize()} + {@link #valueAt}/{@link #keyAt} (Slots mit
 * {@code valueAt == null} überspringen) — bewusst cursor-basiert statt Iterator, damit
 * Frame-Schleifen allokationsfrei bleiben. Während einer Cursor-Iteration darf NICHT
 * mutiert werden; für Entfernen-während-Iteration gibt es {@link #removeIf}.</p>
 */
public final class LongObjMap<V> {

    /** Prädikat für {@link #removeIf}; siehe dortige Idempotenz-Anforderung. */
    public interface LongObjPredicate<V> {
        boolean test(long key, V value);
    }

    private long[] keys;
    private Object[] values;
    private int size;
    private int mask;

    public LongObjMap(int expectedSize) {
        int capacity = Integer.highestOneBit(Math.max(16, expectedSize * 4 / 3 - 1)) << 1;
        this.keys = new long[capacity];
        this.values = new Object[capacity];
        this.mask = capacity - 1;
    }

    @SuppressWarnings("unchecked")
    public V get(long key) {
        int i = index(key) & this.mask;
        while (this.values[i] != null) {
            if (this.keys[i] == key) return (V) this.values[i];
            i = (i + 1) & this.mask;
        }
        return null;
    }

    /** @return der vorherige Wert oder null. */
    @SuppressWarnings("unchecked")
    public V put(long key, V value) {
        if (value == null) throw new IllegalArgumentException("null-Werte sind nicht erlaubt (null = leerer Slot)");
        int i = index(key) & this.mask;
        while (this.values[i] != null) {
            if (this.keys[i] == key) {
                Object old = this.values[i];
                this.values[i] = value;
                return (V) old;
            }
            i = (i + 1) & this.mask;
        }
        this.keys[i] = key;
        this.values[i] = value;
        if (++this.size * 4 > this.mask * 3) this.grow();
        return null;
    }

    /** @return der entfernte Wert oder null (Dispose-Pfade brauchen den alten Wert). */
    @SuppressWarnings("unchecked")
    public V remove(long key) {
        int i = index(key) & this.mask;
        while (this.values[i] != null) {
            if (this.keys[i] == key) {
                Object old = this.values[i];
                this.removeAt(i);
                return (V) old;
            }
            i = (i + 1) & this.mask;
        }
        return null;
    }

    /**
     * Entfernt alle Einträge, für die das Prädikat true liefert. Das Prädikat kann für
     * VERBLEIBENDE Einträge mehrfach aufgerufen werden (Backward-Shift kann Einträge über den
     * Cursor zurückbewegen) — Seiteneffekte gehören deshalb ausschließlich in den true-Zweig
     * (entfernte Einträge werden nie erneut besucht).
     */
    @SuppressWarnings("unchecked")
    public void removeIf(LongObjPredicate<V> predicate) {
        for (int i = 0; i <= this.mask; i++) {
            while (this.values[i] != null && predicate.test(this.keys[i], (V) this.values[i])) {
                this.removeAt(i); // rückt den Cluster nach — denselben Index erneut prüfen
            }
        }
    }

    /** Backward-Shift-Löschung: hält die Sondierketten lückenlos (keine Tombstones). */
    private void removeAt(int i) {
        this.values[i] = null;
        this.size--;
        int j = i;
        while (true) {
            j = (j + 1) & this.mask;
            if (this.values[j] == null) return;
            int ideal = index(this.keys[j]) & this.mask;
            /* Liegt i (die Lücke) zwischen ideal und j, darf der Eintrag zurückrücken. */
            if (((j - ideal) & this.mask) >= ((j - i) & this.mask)) {
                this.keys[i] = this.keys[j];
                this.values[i] = this.values[j];
                this.values[j] = null;
                i = j;
            }
        }
    }

    private void grow() {
        long[] oldKeys = this.keys;
        Object[] oldValues = this.values;
        int capacity = (this.mask + 1) << 1;
        this.keys = new long[capacity];
        this.values = new Object[capacity];
        this.mask = capacity - 1;
        for (int i = 0; i < oldValues.length; i++) {
            if (oldValues[i] == null) continue;
            int j = index(oldKeys[i]) & this.mask;
            while (this.values[j] != null) j = (j + 1) & this.mask;
            this.keys[j] = oldKeys[i];
            this.values[j] = oldValues[i];
        }
    }

    public int size() {
        return this.size;
    }

    public boolean isEmpty() {
        return this.size == 0;
    }

    public void clear() {
        Arrays.fill(this.values, null);
        this.size = 0;
    }

    /* --- Cursor-Iteration (allokationsfrei): Slots mit valueAt == null überspringen. --- */

    public int tableSize() {
        return this.mask + 1;
    }

    @SuppressWarnings("unchecked")
    public V valueAt(int i) {
        return (V) this.values[i];
    }

    /** Nur gültig, wenn {@link #valueAt}(i) != null. */
    public long keyAt(int i) {
        return this.keys[i];
    }

    /** Fibonacci-Streuung: Chunk-/Section-Keys clustern in den unteren Bits. */
    private static int index(long key) {
        long h = key * 0x9E3779B97F4A7C15L;
        return (int) (h >>> 40);
    }
}
