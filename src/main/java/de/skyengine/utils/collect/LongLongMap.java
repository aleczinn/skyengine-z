package de.skyengine.utils.collect;

import java.util.Arrays;

/**
 * Offene long→long-Map (lineares Sondieren, Pow2-Kapazität, Load-Faktor 0,75) — dritte
 * Variante neben {@link LongObjMap}/{@link LongIntMap}, Ersatz für {@code HashMap<Long, Long>}
 * (doppeltes Boxing). NICHT threadsicher.
 *
 * <p>Belegung über ein eigenes {@code used}-Array, Entfernen per Backward-Shift.
 * Iteration cursor-basiert: {@link #tableSize()} + {@link #usedAt}/{@link #keyAt}/{@link #valueAt}.</p>
 */
public final class LongLongMap {

    private long[] keys;
    private long[] values;
    private boolean[] used;
    private int size;
    private int mask;

    public LongLongMap(int expectedSize) {
        int capacity = Integer.highestOneBit(Math.max(16, expectedSize * 4 / 3 - 1)) << 1;
        this.keys = new long[capacity];
        this.values = new long[capacity];
        this.used = new boolean[capacity];
        this.mask = capacity - 1;
    }

    public long getOrDefault(long key, long def) {
        int i = index(key) & this.mask;
        while (this.used[i]) {
            if (this.keys[i] == key) return this.values[i];
            i = (i + 1) & this.mask;
        }
        return def;
    }

    public boolean containsKey(long key) {
        int i = index(key) & this.mask;
        while (this.used[i]) {
            if (this.keys[i] == key) return true;
            i = (i + 1) & this.mask;
        }
        return false;
    }

    public void put(long key, long value) {
        int i = index(key) & this.mask;
        while (this.used[i]) {
            if (this.keys[i] == key) {
                this.values[i] = value;
                return;
            }
            i = (i + 1) & this.mask;
        }
        this.used[i] = true;
        this.keys[i] = key;
        this.values[i] = value;
        if (++this.size * 4 > this.mask * 3) this.grow();
    }

    /** @return true, wenn der Key enthalten war. */
    public boolean remove(long key) {
        int i = index(key) & this.mask;
        while (this.used[i]) {
            if (this.keys[i] == key) {
                this.removeAt(i);
                return true;
            }
            i = (i + 1) & this.mask;
        }
        return false;
    }

    private void removeAt(int i) {
        this.used[i] = false;
        this.size--;
        int j = i;
        while (true) {
            j = (j + 1) & this.mask;
            if (!this.used[j]) return;
            int ideal = index(this.keys[j]) & this.mask;
            if (((j - ideal) & this.mask) >= ((j - i) & this.mask)) {
                this.keys[i] = this.keys[j];
                this.values[i] = this.values[j];
                this.used[j] = false;
                this.used[i] = true;
                i = j;
            }
        }
    }

    private void grow() {
        long[] oldKeys = this.keys;
        long[] oldValues = this.values;
        boolean[] oldUsed = this.used;
        int capacity = (this.mask + 1) << 1;
        this.keys = new long[capacity];
        this.values = new long[capacity];
        this.used = new boolean[capacity];
        this.mask = capacity - 1;
        for (int i = 0; i < oldUsed.length; i++) {
            if (!oldUsed[i]) continue;
            int j = index(oldKeys[i]) & this.mask;
            while (this.used[j]) j = (j + 1) & this.mask;
            this.used[j] = true;
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
        Arrays.fill(this.used, false);
        this.size = 0;
    }

    /* --- Cursor-Iteration (allokationsfrei): Slots mit !usedAt überspringen. --- */

    public int tableSize() {
        return this.mask + 1;
    }

    public boolean usedAt(int i) {
        return this.used[i];
    }

    /** Nur gültig, wenn {@link #usedAt}(i). */
    public long keyAt(int i) {
        return this.keys[i];
    }

    /** Nur gültig, wenn {@link #usedAt}(i). */
    public long valueAt(int i) {
        return this.values[i];
    }

    /** Fibonacci-Streuung: gepackte Positions-Keys clustern in den unteren Bits. */
    private static int index(long key) {
        long h = key * 0x9E3779B97F4A7C15L;
        return (int) (h >>> 40);
    }
}
