package de.skyengine.game.world.tick;

import de.skyengine.game.world.block.Identifier;
import de.skyengine.game.world.chunk.Chunk;
import de.skyengine.utils.collect.LongIntMap;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ScheduledTickQueueTest {

    private static final Identifier STONE = Identifier.of("voxelstories:stone");
    private static final Identifier DIRT = Identifier.of("voxelstories:dirt");

    @Test
    void drainsByTriggerTimeAndFifoWithinSameTick() {
        ScheduledTickQueue queue = new ScheduledTickQueue();
        queue.schedule(1, 2, 3, STONE, 8);
        queue.schedule(4, 5, 6, STONE, 5);
        queue.schedule(7, 8, 9, STONE, 5);

        List<String> fired = new ArrayList<>();
        queue.drainDue(8, (x, y, z, block, trigger, priority, order) -> fired.add(x + "," + y + "," + z));

        assertEquals(List.of("4,5,6", "7,8,9", "1,2,3"), fired);
    }

    @Test
    void drainsSameTimeByVanillaPriorityBeforeSubOrder() {
        ScheduledTickQueue queue = new ScheduledTickQueue();
        queue.schedule(1, 2, 3, STONE, 5, TickPriority.NORMAL.value());
        queue.schedule(4, 5, 6, STONE, 5, TickPriority.HIGH.value());

        List<Integer> fired = new ArrayList<>();
        queue.drainDue(5, (x, y, z, block, trigger, priority, order) -> fired.add(x));

        assertEquals(List.of(4, 1), fired);
    }

    @Test
    void firstScheduleWinsUnlessExplicitlyMovedEarlier() {
        ScheduledTickQueue queue = new ScheduledTickQueue();

        assertTrue(queue.schedule(-20, 64, 33, STONE, 10));
        assertFalse(queue.schedule(-20, 64, 33, STONE, 4));
        assertFalse(queue.scheduleEarlier(-20, 64, 33, STONE, 12));
        assertTrue(queue.scheduleEarlier(-20, 64, 33, STONE, 4));

        List<Integer> firedAtX = new ArrayList<>();
        queue.drainDue(3, (x, y, z, block, trigger, priority, order) -> firedAtX.add(x));
        assertTrue(firedAtX.isEmpty());

        queue.drainDue(4, (x, y, z, block, trigger, priority, order) -> firedAtX.add(x));
        assertEquals(List.of(-20), firedAtX);
        assertFalse(queue.isScheduled(-20, 64, 33, STONE));
    }

    @Test
    void ticksScheduledByConsumerWaitForNextDrain() {
        ScheduledTickQueue queue = new ScheduledTickQueue();
        queue.schedule(0, 10, 0, STONE, 1);

        List<Integer> fired = new ArrayList<>();
        queue.drainDue(1, (x, y, z, block, trigger, priority, order) -> {
            fired.add(x);
            queue.schedule(1, 10, 0, STONE, 1);
        });
        assertEquals(List.of(0), fired);

        queue.drainDue(1, (x, y, z, block, trigger, priority, order) -> fired.add(x));
        assertEquals(List.of(0, 1), fired);
    }

    @Test
    void willTickThisTickContainsOnlyUnrunTicksFromCollectedRound() {
        ScheduledTickQueue queue = new ScheduledTickQueue();
        queue.schedule(0, 64, 0, STONE, 1);
        queue.schedule(1, 64, 0, STONE, 1);
        queue.schedule(2, 64, 0, STONE, 2);

        List<Boolean> observations = new ArrayList<>();
        queue.drainDue(1, (x, y, z, block, trigger, priority, order) -> {
            observations.add(queue.willTickThisTick(x, y, z, block));
            observations.add(queue.willTickThisTick(1, 64, 0, STONE));
            observations.add(queue.willTickThisTick(2, 64, 0, STONE));
        });

        assertEquals(List.of(false, true, false, false, false, false), observations);
        assertFalse(queue.willTickThisTick(0, 64, 0, STONE));
        assertFalse(queue.willTickThisTick(1, 64, 0, STONE));
    }

    @Test
    void pendingSnapshotPreservesNegativeCoordinatesAndOverdueDelay() {
        ScheduledTickQueue queue = new ScheduledTickQueue();
        queue.schedule(-100, 50, -217, STONE, 3);

        List<String> pending = new ArrayList<>();
        queue.forEachPending(10, (x, y, z, block, remaining, priority, order) ->
                pending.add(x + "," + y + "," + z + ":" + remaining));

        assertEquals(List.of("-100,50,-217:-7"), pending);
    }

    @Test
    void differentExpectedBlocksMayOwnTicksAtTheSamePosition() {
        ScheduledTickQueue queue = new ScheduledTickQueue();

        assertTrue(queue.schedule(4, 70, 9, STONE, 5));
        assertTrue(queue.schedule(4, 70, 9, DIRT, 5));

        List<Identifier> fired = new ArrayList<>();
        queue.drainDue(5, (x, y, z, block, trigger, priority, order) -> fired.add(block));
        assertEquals(List.of(STONE, DIRT), fired);
    }

    @Test
    void restoredSubOrderOverridesRestoreInsertionOrder() {
        ScheduledTickQueue queue = new ScheduledTickQueue();
        queue.scheduleRestored(1, 64, 0, STONE, 5, 0, 20);
        queue.scheduleRestored(2, 64, 0, DIRT, 5, 0, 10);

        List<Identifier> fired = new ArrayList<>();
        queue.drainDue(5, (x, y, z, block, trigger, priority, order) -> fired.add(block));

        assertEquals(List.of(DIRT, STONE), fired);
    }

    @Test
    void staleEntryCannotHideOrDuplicateARescheduledTickAtTheSameTime() {
        ScheduledTickQueue queue = new ScheduledTickQueue();
        queue.schedule(1, 64, 0, STONE, 10);
        queue.scheduleEarlier(1, 64, 0, STONE, 5);

        List<Integer> due = new ArrayList<>();
        queue.drainDue(5, (x, y, z, block, trigger, priority, order) -> due.add(x));
        assertEquals(List.of(1), due);

        assertTrue(queue.schedule(1, 64, 0, STONE, 10));
        List<Integer> pending = new ArrayList<>();
        queue.forEachPending(5, (x, y, z, block, remaining, priority, order) ->
                pending.add(remaining));
        assertEquals(List.of(5), pending);

        queue.drainDue(10, (x, y, z, block, trigger, priority, order) -> due.add(x));
        assertEquals(List.of(1, 1), due);
    }

    @Test
    void repeatedEarlierSchedulingCompactsSupersededFarFutureEntries() {
        ScheduledTickQueue queue = new ScheduledTickQueue();
        queue.schedule(1, 64, 1, STONE, 10_000);

        for (int trigger = 9_999; trigger >= 1; trigger--) {
            assertTrue(queue.scheduleEarlier(1, 64, 1, STONE, trigger));
        }

        /* Ein logischer Tick plus höchstens der kleine Kompaktierungs-Slack, nicht 10.000. */
        assertTrue(queue.size() <= 257, "Physische Queue zu groß: " + queue.size());
        List<Integer> fired = new ArrayList<>();
        queue.drainDue(1, (x, y, z, block, trigger, priority, order) -> fired.add(x));
        assertEquals(List.of(1), fired);
    }

    @Test
    void boundedDrainKeepsDeterministicRemainderForFollowingTicks() {
        ScheduledTickQueue queue = new ScheduledTickQueue();
        for (int x = 0; x < 10; x++) queue.schedule(x, 64, 0, STONE, 1);

        List<Integer> fired = new ArrayList<>();
        assertEquals(4, queue.drainDue(1, 4,
                (x, y, z, block, trigger, priority, order) -> fired.add(x)));
        assertEquals(List.of(0, 1, 2, 3), fired);

        assertEquals(4, queue.drainDue(1, 4,
                (x, y, z, block, trigger, priority, order) -> fired.add(x)));
        assertEquals(2, queue.drainDue(1, 4,
                (x, y, z, block, trigger, priority, order) -> fired.add(x)));
        assertEquals(List.of(0, 1, 2, 3, 4, 5, 6, 7, 8, 9), fired);
    }

    @Test
    void nonTickingChunkKeepsOriginalDueTimeWithoutBlockingOthers() {
        ScheduledTickQueue queue = new ScheduledTickQueue();
        queue.schedule(1, 64, 0, STONE, 3);
        queue.schedule(40, 64, 0, STONE, 5);
        List<Integer> fired = new ArrayList<>();

        queue.drainDue(10, 10, (x, y, z) -> x != 1,
                (x, y, z, block, trigger, priority, order) -> fired.add(x));

        assertEquals(List.of(40), fired);
        List<Integer> remaining = new ArrayList<>();
        queue.forEachPending(10, (x, y, z, block, delay, priority, order) ->
                remaining.add(delay));
        assertEquals(List.of(-7), remaining,
                "der geparkte Tick muss Vanillas ursprüngliche Zielzeit behalten");

        queue.drainDue(10, 10, (x, y, z) -> true,
                (x, y, z, block, trigger, priority, order) -> fired.add(x));
        assertEquals(List.of(40, 1), fired);
    }

    @Test
    void chunkBatchRemovalDropsLogicalAndStaleEntriesOnlyInTargetChunks() {
        ScheduledTickQueue queue = new ScheduledTickQueue();
        queue.schedule(3, 64, 4, STONE, 100);
        queue.scheduleEarlier(3, 64, 4, STONE, 80);
        queue.schedule(35, 64, 4, DIRT, 100);
        LongIntMap chunks = new LongIntMap(1);
        chunks.put(Chunk.key(0, 0), 1);

        assertEquals(1, queue.removeChunks(chunks));

        assertFalse(queue.isScheduled(3, 64, 4, STONE));
        assertTrue(queue.isScheduled(35, 64, 4, DIRT));
        assertEquals(1, queue.size(), "auch der veraltete Heap-Eintrag muss verschwinden");
    }
}
