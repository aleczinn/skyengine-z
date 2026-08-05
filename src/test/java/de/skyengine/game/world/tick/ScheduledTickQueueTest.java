package de.skyengine.game.world.tick;

import de.skyengine.game.world.block.Identifier;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ScheduledTickQueueTest {

    private static final Identifier STONE = Identifier.of("skyengine:stone");
    private static final Identifier DIRT = Identifier.of("skyengine:dirt");

    @Test
    void drainsByTriggerTimeAndFifoWithinSameTick() {
        ScheduledTickQueue queue = new ScheduledTickQueue();
        queue.schedule(1, 2, 3, STONE, 8);
        queue.schedule(4, 5, 6, STONE, 5);
        queue.schedule(7, 8, 9, STONE, 5);

        List<String> fired = new ArrayList<>();
        queue.drainDue(8, (x, y, z, block, priority, order) -> fired.add(x + "," + y + "," + z));

        assertEquals(List.of("4,5,6", "7,8,9", "1,2,3"), fired);
    }

    @Test
    void firstScheduleWinsUnlessExplicitlyMovedEarlier() {
        ScheduledTickQueue queue = new ScheduledTickQueue();

        assertTrue(queue.schedule(-20, 64, 33, STONE, 10));
        assertFalse(queue.schedule(-20, 64, 33, STONE, 4));
        assertFalse(queue.scheduleEarlier(-20, 64, 33, STONE, 12));
        assertTrue(queue.scheduleEarlier(-20, 64, 33, STONE, 4));

        List<Integer> firedAtX = new ArrayList<>();
        queue.drainDue(3, (x, y, z, block, priority, order) -> firedAtX.add(x));
        assertTrue(firedAtX.isEmpty());

        queue.drainDue(4, (x, y, z, block, priority, order) -> firedAtX.add(x));
        assertEquals(List.of(-20), firedAtX);
        assertFalse(queue.isScheduled(-20, 64, 33, STONE));
    }

    @Test
    void ticksScheduledByConsumerWaitForNextDrain() {
        ScheduledTickQueue queue = new ScheduledTickQueue();
        queue.schedule(0, 10, 0, STONE, 1);

        List<Integer> fired = new ArrayList<>();
        queue.drainDue(1, (x, y, z, block, priority, order) -> {
            fired.add(x);
            queue.schedule(1, 10, 0, STONE, 1);
        });
        assertEquals(List.of(0), fired);

        queue.drainDue(1, (x, y, z, block, priority, order) -> fired.add(x));
        assertEquals(List.of(0, 1), fired);
    }

    @Test
    void pendingSnapshotPreservesNegativeCoordinatesAndClampsOverdueDelay() {
        ScheduledTickQueue queue = new ScheduledTickQueue();
        queue.schedule(-100, 50, -217, STONE, 3);

        List<String> pending = new ArrayList<>();
        queue.forEachPending(10, (x, y, z, block, remaining, priority, order) ->
                pending.add(x + "," + y + "," + z + ":" + remaining));

        assertEquals(List.of("-100,50,-217:1"), pending);
    }

    @Test
    void differentExpectedBlocksMayOwnTicksAtTheSamePosition() {
        ScheduledTickQueue queue = new ScheduledTickQueue();

        assertTrue(queue.schedule(4, 70, 9, STONE, 5));
        assertTrue(queue.schedule(4, 70, 9, DIRT, 5));

        List<Identifier> fired = new ArrayList<>();
        queue.drainDue(5, (x, y, z, block, priority, order) -> fired.add(block));
        assertEquals(List.of(STONE, DIRT), fired);
    }

    @Test
    void restoredSubOrderOverridesRestoreInsertionOrder() {
        ScheduledTickQueue queue = new ScheduledTickQueue();
        queue.scheduleRestored(1, 64, 0, STONE, 5, 0, 20);
        queue.scheduleRestored(2, 64, 0, DIRT, 5, 0, 10);

        List<Identifier> fired = new ArrayList<>();
        queue.drainDue(5, (x, y, z, block, priority, order) -> fired.add(block));

        assertEquals(List.of(DIRT, STONE), fired);
    }

    @Test
    void staleEntryCannotHideOrDuplicateARescheduledTickAtTheSameTime() {
        ScheduledTickQueue queue = new ScheduledTickQueue();
        queue.schedule(1, 64, 0, STONE, 10);
        queue.scheduleEarlier(1, 64, 0, STONE, 5);

        List<Integer> due = new ArrayList<>();
        queue.drainDue(5, (x, y, z, block, priority, order) -> due.add(x));
        assertEquals(List.of(1), due);

        assertTrue(queue.schedule(1, 64, 0, STONE, 10));
        List<Integer> pending = new ArrayList<>();
        queue.forEachPending(5, (x, y, z, block, remaining, priority, order) ->
                pending.add(remaining));
        assertEquals(List.of(5), pending);

        queue.drainDue(10, (x, y, z, block, priority, order) -> due.add(x));
        assertEquals(List.of(1, 1), due);
    }
}
