package de.skyengine.game.world.debug;

/**
 * Weltgebundene Diagnosezaehler fuer Simulation, Scheduler und Redstone. Die Welt schaltet
 * die Instanz nur im Full-Debug-Modus ein; bei deaktivierter Telemetrie sind alle Record-Hooks
 * fruehe No-ops und Zeitmessungen rufen nicht einmal {@link System#nanoTime()} auf.
 *
 * <p>Nur der Tick-/Render-Thread greift auf eine Instanz zu. Darum sind weder Atomics noch
 * Locks erforderlich.</p>
 */
public final class SimulationTelemetry {

    public record Snapshot(
            long ticks,
            long redstoneNanos,
            long maxRedstoneNanosPerTick,
            long wireWakes,
            long suppressedWireWakes,
            long wireSolves,
            long wireCells,
            long wireWrites,
            long wireReceivers,
            long cappedWireSolves,
            int largestWireCells,
            int largestWireX,
            int largestWireY,
            int largestWireZ,
            long slowestWireNanos,
            int slowestWireX,
            int slowestWireY,
            int slowestWireZ,
            long scheduledAccepted,
            long scheduledDeduplicated,
            long scheduledDue,
            long maxScheduledDuePerTick,
            long scheduledExecuted,
            long scheduledRescheduled,
            long scheduledDroppedUnloaded,
            long scheduledSkippedWrongBlock,
            long scheduledSkippedAir,
            long blockEventWaves,
            long blockEventsProcessed,
            long blockEventWaveLimitHits,
            long blockEventBudgetHits,
            long deferredProcessed,
            long deferredRequeued,
            long deferredDropped) {}

    private boolean enabled;

    private long ticks;
    private long redstoneNanos;
    private long redstoneNanosThisTick;
    private long maxRedstoneNanosPerTick;
    private long wireWakes;
    private long suppressedWireWakes;
    private long wireSolves;
    private long wireCells;
    private long wireWrites;
    private long wireReceivers;
    private long cappedWireSolves;
    private int largestWireCells;
    private int largestWireX, largestWireY, largestWireZ;
    private long slowestWireNanos;
    private int slowestWireX, slowestWireY, slowestWireZ;
    private long scheduledAccepted;
    private long scheduledDeduplicated;
    private long scheduledDue;
    private long scheduledDueThisTick;
    private long maxScheduledDuePerTick;
    private long scheduledExecuted;
    private long scheduledRescheduled;
    private long scheduledDroppedUnloaded;
    private long scheduledSkippedWrongBlock;
    private long scheduledSkippedAir;
    private long blockEventWaves;
    private long blockEventsProcessed;
    private long blockEventWaveLimitHits;
    private long blockEventBudgetHits;
    private long deferredProcessed;
    private long deferredRequeued;
    private long deferredDropped;

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isEnabled() {
        return this.enabled;
    }

    public void beginTick() {
        if (!this.enabled) return;
        this.redstoneNanosThisTick = 0;
        this.scheduledDueThisTick = 0;
        this.ticks++;
    }

    public void endTick() {
        if (!this.enabled) return;
        this.maxRedstoneNanosPerTick = Math.max(this.maxRedstoneNanosPerTick, this.redstoneNanosThisTick);
        this.maxScheduledDuePerTick = Math.max(this.maxScheduledDuePerTick, this.scheduledDueThisTick);
    }

    /** Startwert fuer eine Redstone-Messung; 0 bedeutet Telemetrie aus. */
    public long beginRedstoneTiming() {
        return this.enabled ? System.nanoTime() : 0;
    }

    public void recordWireWake() {
        if (this.enabled) this.wireWakes++;
    }

    public void recordSuppressedWireWake() {
        if (this.enabled) this.suppressedWireWakes++;
    }

    public void recordWireSolve(long startedNanos, int x, int y, int z, int cells, int writes,
                                int receivers, boolean capped) {
        if (!this.enabled) return;
        long elapsed = startedNanos == 0 ? 0 : System.nanoTime() - startedNanos;
        this.redstoneNanos += elapsed;
        this.redstoneNanosThisTick += elapsed;
        this.wireSolves++;
        this.wireCells += cells;
        this.wireWrites += writes;
        this.wireReceivers += receivers;
        if (capped) this.cappedWireSolves++;
        if (cells > this.largestWireCells) {
            this.largestWireCells = cells;
            this.largestWireX = x;
            this.largestWireY = y;
            this.largestWireZ = z;
        }
        if (elapsed > this.slowestWireNanos) {
            this.slowestWireNanos = elapsed;
            this.slowestWireX = x;
            this.slowestWireY = y;
            this.slowestWireZ = z;
        }
    }

    public void recordScheduledRequest(boolean accepted) {
        if (!this.enabled) return;
        if (accepted) this.scheduledAccepted++;
        else this.scheduledDeduplicated++;
    }

    public void recordScheduledDue() {
        if (!this.enabled) return;
        this.scheduledDue++;
        this.scheduledDueThisTick++;
    }

    public void recordScheduledExecuted() {
        if (this.enabled) this.scheduledExecuted++;
    }

    public void recordScheduledRescheduled() {
        if (this.enabled) this.scheduledRescheduled++;
    }

    public void recordScheduledDroppedUnloaded() {
        this.recordScheduledDroppedUnloaded(1);
    }

    public void recordScheduledDroppedUnloaded(int count) {
        if (this.enabled) this.scheduledDroppedUnloaded += count;
    }

    public void recordScheduledSkippedWrongBlock() {
        if (this.enabled) this.scheduledSkippedWrongBlock++;
    }

    public void recordScheduledSkippedAir() {
        if (this.enabled) this.scheduledSkippedAir++;
    }

    public void recordBlockEventWave(int events) {
        if (!this.enabled) return;
        this.blockEventWaves++;
        this.blockEventsProcessed += events;
    }

    public void recordBlockEventWaveLimitHit() {
        if (this.enabled) this.blockEventWaveLimitHits++;
    }

    public void recordBlockEventBudgetHit() {
        if (this.enabled) this.blockEventBudgetHits++;
    }

    public void recordDeferredProcessed(int count) {
        if (this.enabled) this.deferredProcessed += count;
    }

    public void recordDeferredRequeued() {
        if (this.enabled) this.deferredRequeued++;
    }

    public void recordDeferredDropped() {
        if (this.enabled) this.deferredDropped++;
    }

    public Snapshot snapshot() {
        return new Snapshot(this.ticks, this.redstoneNanos, this.maxRedstoneNanosPerTick,
                this.wireWakes, this.suppressedWireWakes, this.wireSolves, this.wireCells,
                this.wireWrites, this.wireReceivers, this.cappedWireSolves,
                this.largestWireCells, this.largestWireX, this.largestWireY, this.largestWireZ,
                this.slowestWireNanos, this.slowestWireX, this.slowestWireY, this.slowestWireZ,
                this.scheduledAccepted, this.scheduledDeduplicated, this.scheduledDue,
                this.maxScheduledDuePerTick, this.scheduledExecuted, this.scheduledRescheduled,
                this.scheduledDroppedUnloaded, this.scheduledSkippedWrongBlock,
                this.scheduledSkippedAir, this.blockEventWaves, this.blockEventsProcessed,
                this.blockEventWaveLimitHits, this.blockEventBudgetHits,
                this.deferredProcessed, this.deferredRequeued,
                this.deferredDropped);
    }

    /** Sekundenzeile fuer den bestehenden Full-Debug-Status; setzt danach das Messfenster zurueck. */
    public String statusLineAndReset() {
        if (!this.enabled || this.ticks == 0) return null;
        long avgRedstoneMicros = this.redstoneNanos / this.ticks / 1_000;
        String line = "SIM ticks=" + this.ticks
                + " | redstone[us] avg=" + avgRedstoneMicros
                + " maxTick=" + this.maxRedstoneNanosPerTick / 1_000
                + " wakes=" + this.wireWakes
                + " suppressed=" + this.suppressedWireWakes
                + " solves=" + this.wireSolves
                + " capped=" + this.cappedWireSolves
                + " cells=" + this.wireCells
                + " writes=" + this.wireWrites
                + " receivers=" + this.wireReceivers
                + " largest=" + this.largestWireCells + "@" + pos(this.largestWireX, this.largestWireY, this.largestWireZ)
                + " slowest=" + this.slowestWireNanos / 1_000 + "@" + pos(this.slowestWireX, this.slowestWireY, this.slowestWireZ)
                + " | sched accepted=" + this.scheduledAccepted
                + " dedup=" + this.scheduledDeduplicated
                + " due=" + this.scheduledDue
                + " maxDuePerTick=" + this.maxScheduledDuePerTick
                + " run=" + this.scheduledExecuted
                + " parked=" + this.scheduledRescheduled
                + " unloaded=" + this.scheduledDroppedUnloaded
                + " wrongBlock=" + this.scheduledSkippedWrongBlock
                + " air=" + this.scheduledSkippedAir
                + " | events waves=" + this.blockEventWaves
                + " processed=" + this.blockEventsProcessed
                + " waveLimit=" + this.blockEventWaveLimitHits
                + " budget=" + this.blockEventBudgetHits
                + " | deferred processed=" + this.deferredProcessed
                + " requeued=" + this.deferredRequeued
                + " dropped=" + this.deferredDropped;
        this.reset();
        return line;
    }

    private static String pos(int x, int y, int z) {
        return x + "," + y + "," + z;
    }

    private void reset() {
        this.ticks = 0;
        this.redstoneNanos = 0;
        this.redstoneNanosThisTick = 0;
        this.maxRedstoneNanosPerTick = 0;
        this.wireWakes = 0;
        this.suppressedWireWakes = 0;
        this.wireSolves = 0;
        this.wireCells = 0;
        this.wireWrites = 0;
        this.wireReceivers = 0;
        this.cappedWireSolves = 0;
        this.largestWireCells = 0;
        this.largestWireX = this.largestWireY = this.largestWireZ = 0;
        this.slowestWireNanos = 0;
        this.slowestWireX = this.slowestWireY = this.slowestWireZ = 0;
        this.scheduledAccepted = 0;
        this.scheduledDeduplicated = 0;
        this.scheduledDue = 0;
        this.scheduledDueThisTick = 0;
        this.maxScheduledDuePerTick = 0;
        this.scheduledExecuted = 0;
        this.scheduledRescheduled = 0;
        this.scheduledDroppedUnloaded = 0;
        this.scheduledSkippedWrongBlock = 0;
        this.scheduledSkippedAir = 0;
        this.blockEventWaves = 0;
        this.blockEventsProcessed = 0;
        this.blockEventWaveLimitHits = 0;
        this.blockEventBudgetHits = 0;
        this.deferredProcessed = 0;
        this.deferredRequeued = 0;
        this.deferredDropped = 0;
    }
}
