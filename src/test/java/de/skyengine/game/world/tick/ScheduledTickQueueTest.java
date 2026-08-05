package de.skyengine.game.world.tick;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ScheduledTickQueueTest {

    @Test
    void drainsByTriggerTimeAndFifoWithinSameTick() {
        ScheduledTickQueue queue = new ScheduledTickQueue();
        queue.schedule(1, 2, 3, 8);
        queue.schedule(4, 5, 6, 5);
        queue.schedule(7, 8, 9, 5);

        List<String> fired = new ArrayList<>();
        queue.drainDue(8, (x, y, z) -> fired.add(x + "," + y + "," + z));

        assertEquals(List.of("4,5,6", "7,8,9", "1,2,3"), fired);
    }

    @Test
    void firstScheduleWinsUnlessExplicitlyMovedEarlier() {
        ScheduledTickQueue queue = new ScheduledTickQueue();

        assertTrue(queue.schedule(-20, 64, 33, 10));
        assertFalse(queue.schedule(-20, 64, 33, 4));
        assertFalse(queue.scheduleEarlier(-20, 64, 33, 12));
        assertTrue(queue.scheduleEarlier(-20, 64, 33, 4));

        List<Integer> firedAtX = new ArrayList<>();
        queue.drainDue(3, (x, y, z) -> firedAtX.add(x));
        assertTrue(firedAtX.isEmpty());

        queue.drainDue(4, (x, y, z) -> firedAtX.add(x));
        assertEquals(List.of(-20), firedAtX);
        assertFalse(queue.isScheduled(-20, 64, 33));
    }

    @Test
    void ticksScheduledByConsumerWaitForNextDrain() {
        ScheduledTickQueue queue = new ScheduledTickQueue();
        queue.schedule(0, 10, 0, 1);

        List<Integer> fired = new ArrayList<>();
        queue.drainDue(1, (x, y, z) -> {
            fired.add(x);
            queue.schedule(1, 10, 0, 1);
        });
        assertEquals(List.of(0), fired);

        queue.drainDue(1, (x, y, z) -> fired.add(x));
        assertEquals(List.of(0, 1), fired);
    }

    @Test
    void pendingSnapshotPreservesNegativeCoordinatesAndClampsOverdueDelay() {
        ScheduledTickQueue queue = new ScheduledTickQueue();
        queue.schedule(-100, 50, -217, 3);

        List<String> pending = new ArrayList<>();
        queue.forEachPending(10, (x, y, z, remaining) ->
                pending.add(x + "," + y + "," + z + ":" + remaining));

        assertEquals(List.of("-100,50,-217:1"), pending);
    }
}
