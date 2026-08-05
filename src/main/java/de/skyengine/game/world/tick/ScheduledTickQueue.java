package de.skyengine.game.world.tick;

import de.skyengine.game.world.block.BlockPos;
import de.skyengine.game.world.block.Identifier;
import de.skyengine.game.world.chunk.Chunk;
import de.skyengine.game.world.chunk.ChunkSection;
import de.skyengine.utils.collect.LongIntMap;
import de.skyengine.utils.collect.LongLongMap;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * Deferred Block-Ticks, geordnet nach Zielzeit, Prioritaet und persistenter Suborder.
 * Dedupliziert wird pro Position UND erwartetem Blocktyp. Ein nach Blockersetzung geplanter
 * Tick wird deshalb nicht von einem alten Tick des Vorgaengerblocks blockiert.
 */
public final class ScheduledTickQueue {

    /** Kleine Queues werden nicht wegen einzelner veralteter Vorzieh-Einträge neu geheapified. */
    private static final int STALE_COMPACTION_SLACK = 256;

    @FunctionalInterface
    public interface DueConsumer {
        void run(int x, int y, int z, Identifier expectedBlock, int priority, long subOrder);
    }

    @FunctionalInterface
    public interface PendingConsumer {
        void accept(int x, int y, int z, Identifier expectedBlock, int remainingTicks,
                    int priority, long subOrder);
    }

    private static final class Entry {
        final int x, y, z;
        final long position;
        final Identifier expectedBlock;
        final long triggerTime;
        final int priority;
        final long subOrder;
        final long sequence;

        Entry(int x, int y, int z, Identifier expectedBlock, long triggerTime,
              int priority, long subOrder, long sequence) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.position = BlockPos.asLong(x, y, z);
            this.expectedBlock = expectedBlock;
            this.triggerTime = triggerTime;
            this.priority = priority;
            this.subOrder = subOrder;
            this.sequence = sequence;
        }
    }

    /** Primitive Positionsindizes je Blocktyp: Zeit plus eindeutige Runtime-Sequenz. */
    private static final class TypeIndex {
        final LongLongMap times = new LongLongMap(64);
        final LongLongMap sequences = new LongLongMap(64);
    }

    private final PriorityQueue<Entry> queue = new PriorityQueue<>((a, b) -> {
        int c = Long.compare(a.triggerTime, b.triggerTime);
        if (c != 0) return c;
        c = Integer.compare(a.priority, b.priority);
        if (c != 0) return c;
        c = Long.compare(a.subOrder, b.subOrder);
        if (c != 0) return c;
        c = Long.compareUnsigned(a.position, b.position);
        if (c != 0) return c;
        c = a.expectedBlock.namespace().compareTo(b.expectedBlock.namespace());
        if (c == 0) c = a.expectedBlock.path().compareTo(b.expectedBlock.path());
        return c != 0 ? c : Long.compare(a.sequence, b.sequence);
    });
    private final Map<Identifier, TypeIndex> scheduled = new HashMap<>();
    private static final long NO_VALUE = Long.MIN_VALUE;
    private long sequenceCounter;
    private long subOrderCounter;
    private long revision;
    private int logicalSize;

    public boolean schedule(int x, int y, int z, Identifier expectedBlock, long triggerTime) {
        return this.schedule(x, y, z, expectedBlock, triggerTime, 0);
    }

    public boolean schedule(int x, int y, int z, Identifier expectedBlock, long triggerTime, int priority) {
        TypeIndex index = this.scheduled.computeIfAbsent(expectedBlock, ignored -> new TypeIndex());
        long position = BlockPos.asLong(x, y, z);
        if (index.times.containsKey(position)) return false;
        return this.add(index, x, y, z, expectedBlock, triggerTime, priority, this.subOrderCounter++);
    }

    public boolean scheduleEarlier(int x, int y, int z, Identifier expectedBlock, long triggerTime) {
        return this.scheduleEarlier(x, y, z, expectedBlock, triggerTime, 0);
    }

    public boolean scheduleEarlier(int x, int y, int z, Identifier expectedBlock,
                                   long triggerTime, int priority) {
        TypeIndex index = this.scheduled.computeIfAbsent(expectedBlock, ignored -> new TypeIndex());
        long position = BlockPos.asLong(x, y, z);
        long current = index.times.getOrDefault(position, NO_VALUE);
        if (current != NO_VALUE && current <= triggerTime) return false;
        return this.add(index, x, y, z, expectedBlock, triggerTime, priority, this.subOrderCounter++);
    }

    /** Wiederherstellung oder Parken mit erhaltener persistenter Reihenfolge. */
    public boolean scheduleRestored(int x, int y, int z, Identifier expectedBlock,
                                    long triggerTime, int priority, long subOrder) {
        if (subOrder < 0 || subOrder == Long.MAX_VALUE) {
            throw new IllegalArgumentException("Ungueltige Tick-Suborder: " + subOrder);
        }
        if (subOrder >= this.subOrderCounter) this.subOrderCounter = subOrder + 1;
        TypeIndex index = this.scheduled.computeIfAbsent(expectedBlock, ignored -> new TypeIndex());
        long position = BlockPos.asLong(x, y, z);
        long current = index.times.getOrDefault(position, NO_VALUE);
        if (current != NO_VALUE && current <= triggerTime) return false;
        return this.add(index, x, y, z, expectedBlock, triggerTime, priority, subOrder);
    }

    private boolean add(TypeIndex index, int x, int y, int z, Identifier expectedBlock,
                        long triggerTime, int priority, long subOrder) {
        long position = BlockPos.asLong(x, y, z);
        boolean replacing = index.times.containsKey(position);
        long sequence = this.sequenceCounter++;
        index.times.put(position, triggerTime);
        index.sequences.put(position, sequence);
        this.queue.add(new Entry(x, y, z, expectedBlock, triggerTime, priority, subOrder, sequence));
        if (!replacing) this.logicalSize++;
        this.revision++;
        this.compactStaleEntriesIfNeeded();
        return true;
    }

    public boolean isScheduled(int x, int y, int z, Identifier expectedBlock) {
        TypeIndex index = this.scheduled.get(expectedBlock);
        return index != null && index.times.containsKey(BlockPos.asLong(x, y, z));
    }

    /**
     * Entfernt alle Runtime-Einträge regulär entladener Chunks in einem Queue-Durchlauf.
     * Deren persistierter Snapshot bleibt die autoritative Quelle für einen späteren Reload.
     * Auch veraltete Heap-Einträge vorgezogener Ticks werden physisch ausgetragen.
     *
     * @return Anzahl entfernter logischer Ticks
     */
    public int removeChunks(LongIntMap chunkKeys) {
        if (chunkKeys.isEmpty() || this.queue.isEmpty()) return 0;
        int removed = 0;
        Iterator<Entry> iterator = this.queue.iterator();
        while (iterator.hasNext()) {
            Entry entry = iterator.next();
            long chunkKey = Chunk.key(entry.x >> ChunkSection.SHIFT, entry.z >> ChunkSection.SHIFT);
            if (!chunkKeys.containsKey(chunkKey)) continue;
            iterator.remove();

            TypeIndex index = this.scheduled.get(entry.expectedBlock);
            if (index == null) continue;
            if (index.times.getOrDefault(entry.position, NO_VALUE) != entry.triggerTime
                    || index.sequences.getOrDefault(entry.position, NO_VALUE) != entry.sequence) continue;
            index.times.remove(entry.position);
            index.sequences.remove(entry.position);
            removed++;
        }
        if (removed == 0) return 0;
        this.scheduled.entrySet().removeIf(entry -> entry.getValue().times.isEmpty());
        this.logicalSize -= removed;
        this.revision += removed;
        return removed;
    }

    /** Verwirft den vollständigen Runtime-Stand, z.B. vor einem autoritativen Chunk-Reload. */
    public void clear() {
        if (this.logicalSize == 0 && this.queue.isEmpty()) return;
        this.queue.clear();
        this.scheduled.clear();
        this.logicalSize = 0;
        this.revision++;
    }

    /** Neu eingeplante Ticks aus dem Consumer laufen erst im naechsten Drain. */
    public void drainDue(long now, DueConsumer consumer) {
        this.drainDue(now, Integer.MAX_VALUE, consumer);
    }

    /**
     * Wie {@link #drainDue(long, DueConsumer)}, aber mit einem Deckel für logisch fällige Ticks.
     * Veraltete Queue-Einträge zählen nicht gegen das Budget.
     *
     * @return Anzahl an den Consumer übergebener Ticks
     */
    public int drainDue(long now, int maxTicks, DueConsumer consumer) {
        if (maxTicks < 0) throw new IllegalArgumentException("Negatives Tick-Budget: " + maxTicks);
        long cutoff = this.sequenceCounter;
        int drained = 0;
        Entry entry;
        while (drained < maxTicks && (entry = this.queue.peek()) != null
                && entry.triggerTime <= now && entry.sequence < cutoff) {
            this.queue.poll();
            TypeIndex index = this.scheduled.get(entry.expectedBlock);
            if (index == null) continue;
            long currentTime = index.times.getOrDefault(entry.position, NO_VALUE);
            long currentSequence = index.sequences.getOrDefault(entry.position, NO_VALUE);
            if (currentTime != entry.triggerTime || currentSequence != entry.sequence) continue;
            index.times.remove(entry.position);
            index.sequences.remove(entry.position);
            if (index.times.isEmpty()) this.scheduled.remove(entry.expectedBlock);
            this.logicalSize--;
            this.revision++;
            consumer.run(entry.x, entry.y, entry.z, entry.expectedBlock, entry.priority, entry.subOrder);
            drained++;
        }
        return drained;
    }

    /** Entfernt ersetzte Fern-Ticks, bevor ihre alte Zielzeit die PriorityQueue erreicht. */
    private void compactStaleEntriesIfNeeded() {
        if (this.queue.size() <= this.logicalSize * 2 + STALE_COMPACTION_SLACK) return;
        this.queue.removeIf(entry -> {
            TypeIndex index = this.scheduled.get(entry.expectedBlock);
            return index == null
                    || index.times.getOrDefault(entry.position, NO_VALUE) != entry.triggerTime
                    || index.sequences.getOrDefault(entry.position, NO_VALUE) != entry.sequence;
        });
    }

    /** Meldet nur die jeweils massgeblichen Eintraege; Reihenfolge ist unspezifiziert. */
    public void forEachPending(long now, PendingConsumer consumer) {
        for (Entry entry : this.queue) {
            TypeIndex index = this.scheduled.get(entry.expectedBlock);
            if (index == null) continue;
            if (index.times.getOrDefault(entry.position, NO_VALUE) != entry.triggerTime
                    || index.sequences.getOrDefault(entry.position, NO_VALUE) != entry.sequence) continue;
            long remainingLong = entry.triggerTime <= now ? 1 : entry.triggerTime - now;
            int remaining = (int) Math.min(Integer.MAX_VALUE, remainingLong);
            consumer.accept(entry.x, entry.y, entry.z, entry.expectedBlock, remaining,
                    entry.priority, entry.subOrder);
        }
    }

    /** PriorityQueue-Laenge inklusive veralteter Eintraege; nur Telemetrie. */
    public int size() {
        return this.queue.size();
    }

    /** Aendert sich bei jeder logischen Queue-Mutation; invalidiert Save-Snapshot-Indizes. */
    public long revision() {
        return this.revision;
    }
}
