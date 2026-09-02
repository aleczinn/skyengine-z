package de.skyengine.server.profile;

import java.util.Arrays;
import java.util.EnumMap;
import java.util.Map;

/** Tick-thread profiler with a fixed, allocation-free rolling window. */
public final class ServerProfiler {
    public enum Phase {
        SERVER_TICK_TOTAL,
        NETWORK_INPUT,
        PLAYER_SIMULATION,
        CHUNK_SIMULATION,
        BLOCK_ENTITIES,
        ENTITIES,
        REPLICATION,
        PERSISTENCE
    }

    public record Stats(double medianMillis, double p95Millis, double maximumMillis, int samples) {}

    private static final int WINDOW = 1200;
    private final EnumMap<Phase, long[]> samples = new EnumMap<>(Phase.class);
    private final EnumMap<Phase, Long> starts = new EnumMap<>(Phase.class);
    private int cursor;
    private int count;

    public ServerProfiler() {
        for (Phase phase : Phase.values()) this.samples.put(phase, new long[WINDOW]);
    }

    public void begin(Phase phase) { this.starts.put(phase, System.nanoTime()); }

    public void end(Phase phase) {
        Long start = this.starts.remove(phase);
        if (start == null) throw new IllegalStateException("Profiler phase was not started: " + phase);
        this.samples.get(phase)[this.cursor] += System.nanoTime() - start;
    }

    public void finishTick() {
        this.cursor = (this.cursor + 1) % WINDOW;
        if (this.count < WINDOW) this.count++;
        for (long[] phaseSamples : this.samples.values()) phaseSamples[this.cursor] = 0;
    }

    public Stats stats(Phase phase) {
        if (this.count == 0) return new Stats(0, 0, 0, 0);
        long[] copy = new long[this.count];
        long[] source = this.samples.get(phase);
        for (int i = 0; i < this.count; i++) copy[i] = source[i];
        Arrays.sort(copy);
        return new Stats(copy[(copy.length - 1) / 2] / 1_000_000.0,
                copy[(int) Math.ceil(copy.length * 0.95) - 1] / 1_000_000.0,
                copy[copy.length - 1] / 1_000_000.0, copy.length);
    }

    public Map<Phase, Stats> snapshot() {
        EnumMap<Phase, Stats> result = new EnumMap<>(Phase.class);
        for (Phase phase : Phase.values()) result.put(phase, stats(phase));
        return Map.copyOf(result);
    }
}
