package de.skyengine.server.world;

import java.util.Arrays;
import java.util.function.LongConsumer;

/** Small allocation-free primitive set used by per-player spatial interest tracking. */
final class LongHashSet {
    private long[] keys;
    private byte[] states;
    private int size;
    private int resizeAt;

    LongHashSet(int expectedSize) {
        int capacity = 16;
        while (capacity < expectedSize * 2) capacity <<= 1;
        this.keys = new long[capacity];
        this.states = new byte[capacity];
        this.resizeAt = capacity * 2 / 3;
    }

    int size() { return this.size; }

    boolean contains(long key) {
        int mask = this.keys.length - 1;
        int slot = mix(key) & mask;
        while (this.states[slot] != 0) {
            if (this.states[slot] == 1 && this.keys[slot] == key) return true;
            slot = (slot + 1) & mask;
        }
        return false;
    }

    boolean add(long key) {
        if (this.size >= this.resizeAt) resize();
        int mask = this.keys.length - 1;
        int slot = mix(key) & mask;
        int deleted = -1;
        while (this.states[slot] != 0) {
            if (this.states[slot] == 1 && this.keys[slot] == key) return false;
            if (this.states[slot] == 2 && deleted < 0) deleted = slot;
            slot = (slot + 1) & mask;
        }
        if (deleted >= 0) slot = deleted;
        this.states[slot] = 1;
        this.keys[slot] = key;
        this.size++;
        return true;
    }

    boolean remove(long key) {
        int mask = this.keys.length - 1;
        int slot = mix(key) & mask;
        while (this.states[slot] != 0) {
            if (this.states[slot] == 1 && this.keys[slot] == key) {
                this.states[slot] = 2;
                this.size--;
                return true;
            }
            slot = (slot + 1) & mask;
        }
        return false;
    }

    void forEach(LongConsumer consumer) {
        for (int i = 0; i < this.keys.length; i++) if (this.states[i] == 1) consumer.accept(this.keys[i]);
    }

    private void resize() {
        long[] oldKeys = this.keys;
        byte[] oldStates = this.states;
        this.keys = new long[oldKeys.length << 1];
        this.states = new byte[this.keys.length];
        this.resizeAt = this.keys.length * 2 / 3;
        this.size = 0;
        for (int i = 0; i < oldKeys.length; i++) if (oldStates[i] == 1) add(oldKeys[i]);
    }

    private static int mix(long value) {
        value ^= value >>> 33;
        value *= 0xff51afd7ed558ccdL;
        value ^= value >>> 33;
        value *= 0xc4ceb9fe1a85ec53L;
        return (int) (value ^ (value >>> 32));
    }
}
