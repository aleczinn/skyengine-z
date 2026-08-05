package de.skyengine.game.world;

import de.skyengine.game.world.block.Blocks;
import de.skyengine.game.world.chunk.Chunk;
import de.skyengine.game.world.chunk.ChunkManager;
import de.skyengine.game.world.chunk.ChunkStatus;
import de.skyengine.game.world.save.LevelData;
import de.skyengine.game.world.tick.SavedTick;
import de.skyengine.test.BlocksTestBootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class WorldScheduledTickPersistenceTest {

    @BeforeAll
    static void bootstrapBlocks() {
        BlocksTestBootstrap.ensureBootstrapped();
    }

    @Test
    void consumedNoOpTickInvalidatesItsPersistedSnapshot() throws Exception {
        TestWorld world = new TestWorld();
        Chunk chunk = readyPistonChunk();
        world.install(chunk);
        world.scheduleTick(3, 64, 4, 1);

        List<SavedTick> saved = world.snapshotScheduledTicks(chunk);
        assertEquals(1, saved.size());
        chunk.markSaved(chunk.modificationEpoch());
        assertFalse(chunk.isModified());

        world.advanceGameTime();
        world.tickScheduled();

        assertTrue(chunk.isModified(), "auch ein zustandsloser Tick-Verbrauch braucht eine neue Save-Epoch");
        assertNull(world.snapshotScheduledTicks(chunk));
    }

    @Test
    void rescheduledTickMarksChunkModifiedWhileItIsNotReady() throws Exception {
        TestWorld world = new TestWorld();
        Chunk chunk = readyPistonChunk();
        world.install(chunk);
        world.scheduleTick(3, 64, 4, 1);
        chunk.markSaved(chunk.modificationEpoch());
        chunk.status = ChunkStatus.LIT;

        world.advanceGameTime();
        world.tickScheduled();

        assertTrue(chunk.isModified(), "Re-Schedule während Remesh darf nicht am READY-Gate verloren gehen");
        List<SavedTick> rescheduled = world.snapshotScheduledTicks(chunk);
        assertEquals(1, rescheduled.size());
        assertEquals(20, rescheduled.getFirst().remainingTicks());
    }

    @Test
    void deduplicatedTickRequestsDoNotCreateSaveWork() throws Exception {
        TestWorld world = new TestWorld();
        Chunk chunk = readyPistonChunk();
        world.install(chunk);
        world.scheduleTick(3, 64, 4, 5);
        chunk.markSaved(chunk.modificationEpoch());

        world.scheduleTick(3, 64, 4, 2);
        world.scheduleTickEarlier(3, 64, 4, 8);

        assertFalse(chunk.isModified(), "abgelehnte Queue-Anträge dürfen keine Save-Epoch erzeugen");
        List<SavedTick> pending = world.snapshotScheduledTicks(chunk);
        assertEquals(1, pending.size());
        assertEquals(5, pending.getFirst().remainingTicks());
    }

    @Test
    void acceptedEarlierTickRequestStillCreatesSaveWork() throws Exception {
        TestWorld world = new TestWorld();
        Chunk chunk = readyPistonChunk();
        world.install(chunk);
        world.scheduleTick(3, 64, 4, 8);
        chunk.markSaved(chunk.modificationEpoch());

        world.scheduleTickEarlier(3, 64, 4, 2);

        assertTrue(chunk.isModified(), "ein echtes Vorziehen verändert den persistenten Tick-Zustand");
        List<SavedTick> pending = world.snapshotScheduledTicks(chunk);
        assertEquals(1, pending.size());
        assertEquals(2, pending.getFirst().remainingTicks());
    }

    @Test
    void unloadedChunkDropsRuntimeTicksAndEventsButKeepsLoadedNeighbors() throws Exception {
        TestWorld world = new TestWorld();
        Chunk unloaded = readyPistonChunk();
        Chunk neighbor = new Chunk(1, 0);
        neighbor.status = ChunkStatus.READY;
        neighbor.setBlock(3, 64, 4, Blocks.PISTON);
        world.install(unloaded);
        world.install(neighbor);
        world.scheduleTick(3, 64, 4, 100);
        world.enqueueBlockEvent(3, 64, 4);
        world.scheduleTick(35, 64, 4, 100);

        assertEquals(2, world.snapshotScheduledTicks(unloaded).size());
        world.unload(unloaded);
        world.processUnloadedChunks();

        assertNull(world.snapshotScheduledTicks(unloaded));
        assertEquals(1, world.snapshotScheduledTicks(neighbor).size());
        assertTrue(world.isTickScheduled(35, 64, 4));
    }

    private static Chunk readyPistonChunk() {
        Chunk chunk = new Chunk(0, 0);
        chunk.status = ChunkStatus.READY;
        chunk.setBlock(3, 64, 4, Blocks.PISTON);
        return chunk;
    }

    private static final class TestWorld extends World {
        private static final Field CHUNKS_FIELD;
        private static final Field CHUNK_MANAGER_FIELD;
        private static final Field GAME_TIME_FIELD;
        private static final Field UNLOAD_QUEUE_FIELD;
        private static final Method TICK_SCHEDULED;
        private static final Method PROCESS_UNLOADED;

        static {
            try {
                CHUNKS_FIELD = ChunkManager.class.getDeclaredField("chunks");
                CHUNKS_FIELD.setAccessible(true);
                CHUNK_MANAGER_FIELD = World.class.getDeclaredField("chunkManager");
                CHUNK_MANAGER_FIELD.setAccessible(true);
                GAME_TIME_FIELD = World.class.getDeclaredField("gameTime");
                GAME_TIME_FIELD.setAccessible(true);
                UNLOAD_QUEUE_FIELD = ChunkManager.class.getDeclaredField("unloadAnnounceQueue");
                UNLOAD_QUEUE_FIELD.setAccessible(true);
                TICK_SCHEDULED = World.class.getDeclaredMethod("tickScheduled");
                TICK_SCHEDULED.setAccessible(true);
                PROCESS_UNLOADED = World.class.getDeclaredMethod("processUnloadedChunkBoundaries");
                PROCESS_UNLOADED.setAccessible(true);
            } catch (ReflectiveOperationException e) {
                throw new ExceptionInInitializerError(e);
            }
        }

        TestWorld() {
            super("__scheduled_tick_persistence_test", level(), null, null);
        }

        @SuppressWarnings("unchecked")
        void install(Chunk chunk) throws IllegalAccessException {
            ChunkManager manager = (ChunkManager) CHUNK_MANAGER_FIELD.get(this);
            ((Map<Long, Chunk>) CHUNKS_FIELD.get(manager))
                    .put(Chunk.key(chunk.chunkX, chunk.chunkZ), chunk);
        }

        void advanceGameTime() throws IllegalAccessException {
            GAME_TIME_FIELD.setLong(this, GAME_TIME_FIELD.getLong(this) + 1);
        }

        void tickScheduled() throws ReflectiveOperationException {
            TICK_SCHEDULED.invoke(this);
        }

        @SuppressWarnings("unchecked")
        void unload(Chunk chunk) throws IllegalAccessException {
            ChunkManager manager = (ChunkManager) CHUNK_MANAGER_FIELD.get(this);
            ((Map<Long, Chunk>) CHUNKS_FIELD.get(manager)).remove(Chunk.key(chunk.chunkX, chunk.chunkZ));
            ((ConcurrentLinkedQueue<Long>) UNLOAD_QUEUE_FIELD.get(manager))
                    .add(Chunk.key(chunk.chunkX, chunk.chunkZ));
        }

        void processUnloadedChunks() throws ReflectiveOperationException {
            PROCESS_UNLOADED.invoke(this);
        }

        private static LevelData level() {
            LevelData level = new LevelData();
            level.name = "scheduled-tick-persistence-test";
            level.seed = 1;
            level.worldType = "imported";
            return level;
        }
    }
}
