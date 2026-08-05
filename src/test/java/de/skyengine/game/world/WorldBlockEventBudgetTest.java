package de.skyengine.game.world;

import de.skyengine.game.world.save.LevelData;
import de.skyengine.test.BlocksTestBootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class WorldBlockEventBudgetTest {

    @BeforeAll
    static void bootstrapBlocks() {
        BlocksTestBootstrap.ensureBootstrapped();
    }

    @Test
    void fullEventQueueFallsBackToPersistentScheduledTicks() throws Exception {
        TestWorld world = new TestWorld();

        for (int x = 0; x < 5_000; x++) world.enqueueBlockEvent(x, 64, 0);

        assertEquals(4_096, world.blockEventCount());
        assertTrue(world.isTickScheduled(4_999, 64, 0));
    }

    @Test
    void bothDrainPointsShareOneBudgetAndKeepTheRemainder() throws Exception {
        TestWorld world = new TestWorld();
        for (int x = 0; x < 4_096; x++) world.enqueueBlockEvent(x, 64, 0);

        world.processBlockEvents();
        assertEquals(0, world.blockEventCount());

        for (int x = 0; x < 10; x++) world.enqueueBlockEvent(x, 64, 1);
        world.processBlockEvents();
        assertEquals(10, world.blockEventCount());

        world.advanceGameTime();
        world.processBlockEvents();
        assertEquals(0, world.blockEventCount());
    }

    private static final class TestWorld extends World {
        private static final Field BLOCK_EVENTS_FIELD;
        private static final Field GAME_TIME_FIELD;
        private static final Method PROCESS_BLOCK_EVENTS;

        static {
            try {
                BLOCK_EVENTS_FIELD = World.class.getDeclaredField("blockEvents");
                BLOCK_EVENTS_FIELD.setAccessible(true);
                GAME_TIME_FIELD = World.class.getDeclaredField("gameTime");
                GAME_TIME_FIELD.setAccessible(true);
                PROCESS_BLOCK_EVENTS = World.class.getDeclaredMethod("processBlockEvents");
                PROCESS_BLOCK_EVENTS.setAccessible(true);
            } catch (ReflectiveOperationException e) {
                throw new ExceptionInInitializerError(e);
            }
        }

        TestWorld() {
            super("__block_event_budget_test", level(), null, null);
        }

        int blockEventCount() throws IllegalAccessException {
            return ((Set<?>) BLOCK_EVENTS_FIELD.get(this)).size();
        }

        void advanceGameTime() throws IllegalAccessException {
            GAME_TIME_FIELD.setLong(this, GAME_TIME_FIELD.getLong(this) + 1);
        }

        void processBlockEvents() throws ReflectiveOperationException {
            PROCESS_BLOCK_EVENTS.invoke(this);
        }

        private static LevelData level() {
            LevelData level = new LevelData();
            level.name = "block-event-budget-test";
            level.seed = 1;
            level.worldType = "imported";
            return level;
        }
    }
}
