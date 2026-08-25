package de.skyengine.game.world;

import de.skyengine.game.world.block.Blocks;
import de.skyengine.game.world.chunk.Chunk;
import de.skyengine.game.world.chunk.ChunkManager;
import de.skyengine.game.world.chunk.ChunkStatus;
import de.skyengine.game.world.save.ChunkSerializer;
import de.skyengine.game.world.save.LevelData;
import de.skyengine.game.world.tick.SavedTick;
import de.skyengine.test.BlocksTestBootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Collection;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class WorldBlockEventBudgetTest {

    @BeforeAll
    static void bootstrapBlocks() {
        BlocksTestBootstrap.ensureBootstrapped();
    }

    @Test
    void vanillaQueueKeepsMoreThan4096EventsWithoutChangingTheirType() throws Exception {
        TestWorld world = new TestWorld();

        for (int x = 0; x < 5_000; x++) world.enqueueBlockEvent(x, 64, 0);

        assertEquals(5_000, world.blockEventCount());
        assertFalse(world.isTickScheduled(4_999, 64, 0));
    }

    @Test
    void eventsOutsideSimulationKeepTheirCompleteDataForTheNextTick() throws Exception {
        TestWorld world = new TestWorld();
        for (int x = 0; x < 4_096; x++) world.enqueueBlockEvent(x, 64, 0);

        world.processBlockEvents();
        assertEquals(0, world.blockEventCount());
        assertEquals(4_096, world.rescheduledBlockEventCount());

        for (int x = 0; x < 10; x++) world.enqueueBlockEvent(x, 64, 1);
        world.processBlockEvents();
        assertEquals(0, world.blockEventCount());
        assertEquals(4_106, world.rescheduledBlockEventCount());

        world.advanceGameTime();
        world.processBlockEvents();
        assertEquals(0, world.blockEventCount());
        assertEquals(4_106, world.rescheduledBlockEventCount());
    }

    @Test
    void pendingEventSurvivesSaveAndItsRemovalInvalidatesTheSnapshot() throws Exception {
        TestWorld world = new TestWorld();
        Chunk chunk = new Chunk(0, 0);
        chunk.status = ChunkStatus.READY;
        chunk.setBlock(3, 64, 4, Blocks.PISTON);
        world.install(chunk);
        chunk.markSaved(chunk.modificationEpoch());

        world.enqueueBlockEvent(3, 64, 4, 2, 5);
        assertTrue(chunk.isModified(), "ein wartendes Event ist persistenter Chunk-Zustand");
        List<SavedTick> saved = world.snapshotScheduledTicks(chunk);
        assertEquals(1, saved.size());
        SavedTick event = saved.getFirst();
        assertEquals("block_event", event.type());
        assertEquals("skyengine:piston", event.expectedBlock());
        assertEquals(1, event.remainingTicks());
        assertEquals(Integer.MAX_VALUE, event.priority());
        byte[] payload = ChunkSerializer.serialize(chunk, "test", 1, false, saved, List.of());
        Chunk restored = new Chunk(0, 0);
        ChunkSerializer.deserialize(restored, payload, null);
        assertEquals(saved, restored.pendingScheduledTicks);

        /* Simuliert den erfolgreichen IO-Abschluss genau dieses Event-Snapshots. */
        chunk.markSaved(chunk.modificationEpoch());
        assertFalse(chunk.isModified());
        world.processBlockEvents();

        assertEquals(0, world.blockEventCount());
        assertTrue(chunk.isModified(), "auch das Entfernen des gespeicherten Events muss gespeichert werden");
        assertNull(world.snapshotScheduledTicks(chunk));
    }

    @Test
    void onlyCompletelyIdenticalEventsAreDeduplicated() throws Exception {
        TestWorld world = new TestWorld();

        world.enqueueBlockEvent(3, 64, 4, 1, 5);
        world.enqueueBlockEvent(3, 64, 4, 1, 5);
        world.enqueueBlockEvent(3, 64, 4, 2, 5);

        assertEquals(2, world.blockEventCount());
    }

    private static final class TestWorld extends Dimension {
        private static final Field BLOCK_EVENTS_FIELD;
        private static final Field RESCHEDULED_BLOCK_EVENTS_FIELD;
        private static final Field GAME_TIME_FIELD;
        private static final Field CHUNKS_FIELD;
        private static final Method PROCESS_BLOCK_EVENTS;

        static {
            try {
                BLOCK_EVENTS_FIELD = Dimension.class.getDeclaredField("blockEvents");
                BLOCK_EVENTS_FIELD.setAccessible(true);
                RESCHEDULED_BLOCK_EVENTS_FIELD = Dimension.class.getDeclaredField("blockEventsToReschedule");
                RESCHEDULED_BLOCK_EVENTS_FIELD.setAccessible(true);
                GAME_TIME_FIELD = Dimension.class.getDeclaredField("gameTime");
                GAME_TIME_FIELD.setAccessible(true);
                CHUNKS_FIELD = ChunkManager.class.getDeclaredField("chunks");
                CHUNKS_FIELD.setAccessible(true);
                PROCESS_BLOCK_EVENTS = Dimension.class.getDeclaredMethod("processBlockEvents");
                PROCESS_BLOCK_EVENTS.setAccessible(true);
            } catch (ReflectiveOperationException e) {
                throw new ExceptionInInitializerError(e);
            }
        }

        TestWorld() {
            super("__block_event_budget_test", level(), null, null);
        }

        int blockEventCount() throws IllegalAccessException {
            return ((Collection<?>) BLOCK_EVENTS_FIELD.get(this)).size();
        }

        int rescheduledBlockEventCount() throws IllegalAccessException {
            return ((Collection<?>) RESCHEDULED_BLOCK_EVENTS_FIELD.get(this)).size();
        }

        @SuppressWarnings("unchecked")
        void install(Chunk chunk) throws ReflectiveOperationException {
            Field managerField = Dimension.class.getDeclaredField("chunkManager");
            managerField.setAccessible(true);
            ChunkManager manager = (ChunkManager) managerField.get(this);
            ((Map<Long, Chunk>) CHUNKS_FIELD.get(manager))
                    .put(Chunk.key(chunk.chunkX, chunk.chunkZ), chunk);
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
