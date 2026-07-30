package de.skyengine.game.world.tick;

import de.skyengine.game.world.block.BlockPos;
import de.skyengine.utils.collect.LongLongMap;

import java.util.PriorityQueue;

/**
 * Deferred Block-Ticks, geordnet nach Ziel-Tick (dann FIFO). Pro Position ist nur EIN Tick
 * gleichzeitig vorgemerkt (Dedup) - wie in Minecraft. Bewusst entkoppelt von der World
 * (kennt nur Koordinaten + Zeit), damit die Reihenfolge-Logik isoliert testbar bleibt.
 *
 * <p>Basis für zeitbasierte Mechaniken (Flüssigkeits-Ausbreitung, fallender Sand,
 * verzögerte Reaktionen). Verbraucher schedulen sich i.d.R. selbst neu (Kaskade über
 * Re-Scheduling statt synchroner Nachbar-Kaskade).
 */
public final class ScheduledTickQueue {

    /** Callback für einen fälligen Tick an Weltkoordinaten. */
    @FunctionalInterface
    public interface DueConsumer {
        void run(int x, int y, int z);
    }

    private static final class Entry {
        final int x, y, z;
        final long triggerTime;
        final long seq;

        Entry(int x, int y, int z, long triggerTime, long seq) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.triggerTime = triggerTime;
            this.seq = seq;
        }
    }

    private final PriorityQueue<Entry> queue = new PriorityQueue<>((a, b) -> {
        int c = Long.compare(a.triggerTime, b.triggerTime);
        return c != 0 ? c : Long.compare(a.seq, b.seq);
    });
    /* Position -> maßgebliche (früheste) eingeplante Trigger-Zeit. Ältere Queue-Entries sind
       Karteileichen. LongLongMap statt HashMap<Long,Long>: bei fließendem Wasser laufen hier
       hunderte Zugriffe pro Tick — das doppelte Boxing (Key UND Value) entfällt. */
    private final LongLongMap scheduledTime = new LongLongMap(256);
    /** Sentinel für „keine Zeit vorgemerkt" — gameTime ist nie negativ. */
    private static final long NO_TIME = Long.MIN_VALUE;
    private long seqCounter;

    /** Merkt einen Tick zur {@code triggerTime} vor. false, wenn an der Position bereits einer ansteht (first-wins). */
    public boolean schedule(int x, int y, int z, long triggerTime) {
        long key = BlockPos.asLong(x, y, z);
        if (this.scheduledTime.containsKey(key)) return false;
        this.scheduledTime.put(key, triggerTime);
        this.queue.add(new Entry(x, y, z, triggerTime, this.seqCounter++));
        return true;
    }

    /**
     * Plant einen Tick vor; ein bereits anstehender <em>späterer</em> Tick wird auf diese frühere Zeit
     * vorgezogen (der alte Entry wird zur Karteileiche). Gibt es schon einen gleich frühen/früheren,
     * passiert nichts. false, wenn nichts geändert wurde.
     */
    public boolean scheduleEarlier(int x, int y, int z, long triggerTime) {
        long key = BlockPos.asLong(x, y, z);
        long cur = this.scheduledTime.getOrDefault(key, NO_TIME);
        if (cur != NO_TIME && cur <= triggerTime) return false;
        this.scheduledTime.put(key, triggerTime);
        this.queue.add(new Entry(x, y, z, triggerTime, this.seqCounter++));
        return true;
    }

    public boolean isScheduled(int x, int y, int z) {
        return this.scheduledTime.containsKey(BlockPos.asLong(x, y, z));
    }

    /**
     * Führt alle bis einschließlich {@code now} fälligen Ticks in zeitlicher Reihenfolge aus.
     * Während des Laufs neu geplante Ticks (z.B. Re-Scheduling durch den Consumer) werden erst
     * im nächsten Lauf berücksichtigt - das garantiert Termination auch bei Delay 0.
     */
    public void drainDue(long now, DueConsumer consumer) {
        long cutoff = this.seqCounter;
        Entry e;
        while ((e = this.queue.peek()) != null && e.triggerTime <= now && e.seq < cutoff) {
            this.queue.poll();
            long key = BlockPos.asLong(e.x, e.y, e.z);
            long cur = this.scheduledTime.getOrDefault(key, NO_TIME);
            /* Karteileiche überspringen: durch scheduleEarlier vorgezogen oder bereits abgearbeitet. */
            if (cur != e.triggerTime) continue;
            this.scheduledTime.remove(key);
            consumer.run(e.x, e.y, e.z);
        }
    }

    /** Callback für einen anstehenden Tick (Weltkoordinaten + Rest-Delay ab now, min. 1). */
    @FunctionalInterface
    public interface PendingConsumer {
        void accept(int x, int y, int z, int remainingTicks);
    }

    /**
     * Meldet alle anstehenden Ticks (Reihenfolge unspezifiziert) — Basis für die
     * Chunk-Persistenz. Iteriert die scheduledTime-Map (die Wahrheit OHNE die
     * Karteileichen der PriorityQueue). Bereits fällige Ticks melden Rest-Delay 1
     * (früher als der nächste Tick geht nicht). Nur Tick-Thread.
     */
    public void forEachPending(long now, PendingConsumer consumer) {
        for (int i = 0, n = this.scheduledTime.tableSize(); i < n; i++) {
            if (!this.scheduledTime.usedAt(i)) continue;
            long key = this.scheduledTime.keyAt(i);
            int remaining = (int) Math.max(1, this.scheduledTime.valueAt(i) - now);
            consumer.accept(BlockPos.unpackX(key), BlockPos.unpackY(key), BlockPos.unpackZ(key), remaining);
        }
    }

    /** Länge der PriorityQueue — INKLUSIVE Karteileichen (nur Telemetrie, keine echte Tick-Zahl). */
    public int size() {
        return this.queue.size();
    }
}
