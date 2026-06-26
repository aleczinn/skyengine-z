package de.skyengine.game.world.tick;

import java.util.HashSet;
import java.util.PriorityQueue;
import java.util.Set;

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
    private final Set<Long> scheduled = new HashSet<>();
    private long seqCounter;

    /** Merkt einen Tick zur {@code triggerTime} vor. false, wenn an der Position bereits einer ansteht. */
    public boolean schedule(int x, int y, int z, long triggerTime) {
        if (!this.scheduled.add(pack(x, y, z))) return false;
        this.queue.add(new Entry(x, y, z, triggerTime, this.seqCounter++));
        return true;
    }

    public boolean isScheduled(int x, int y, int z) {
        return this.scheduled.contains(pack(x, y, z));
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
            this.scheduled.remove(pack(e.x, e.y, e.z));
            consumer.run(e.x, e.y, e.z);
        }
    }

    public int size() {
        return this.queue.size();
    }

    /* 26 Bit x | 26 Bit z | 12 Bit y - für Dedup eindeutig bei praktischen Weltgrößen. */
    private static long pack(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF);
    }
}
