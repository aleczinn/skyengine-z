package de.skyengine.game.world.tick;

import java.util.HashMap;
import java.util.Map;
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
    /** Position -> maßgebliche (früheste) eingeplante Trigger-Zeit. Ältere Queue-Entries sind Karteileichen. */
    private final Map<Long, Long> scheduledTime = new HashMap<>();
    private long seqCounter;

    /** Merkt einen Tick zur {@code triggerTime} vor. false, wenn an der Position bereits einer ansteht (first-wins). */
    public boolean schedule(int x, int y, int z, long triggerTime) {
        long key = pack(x, y, z);
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
        long key = pack(x, y, z);
        Long cur = this.scheduledTime.get(key);
        if (cur != null && cur <= triggerTime) return false;
        this.scheduledTime.put(key, triggerTime);
        this.queue.add(new Entry(x, y, z, triggerTime, this.seqCounter++));
        return true;
    }

    public boolean isScheduled(int x, int y, int z) {
        return this.scheduledTime.containsKey(pack(x, y, z));
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
            long key = pack(e.x, e.y, e.z);
            Long cur = this.scheduledTime.get(key);
            /* Karteileiche überspringen: durch scheduleEarlier vorgezogen oder bereits abgearbeitet. */
            if (cur == null || cur != e.triggerTime) continue;
            this.scheduledTime.remove(key);
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
