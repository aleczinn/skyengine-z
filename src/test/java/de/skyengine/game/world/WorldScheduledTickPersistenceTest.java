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
import java.util.ArrayList;
import java.util.HashMap;
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
    void dueTickKeepsOriginalTimeWithoutDirtyingChunkWhileItIsNotReady() throws Exception {
        TestWorld world = new TestWorld();
        Chunk chunk = readyPistonChunk();
        world.install(chunk);
        world.scheduleTick(3, 64, 4, 1);
        chunk.markSaved(chunk.modificationEpoch());
        chunk.status = ChunkStatus.LIT;

        world.advanceGameTime();
        world.tickScheduled();

        assertFalse(chunk.isModified(),
                "Vanilla lässt den Tick unverändert in der Chunk-Queue statt ihn neu zu planen");
        List<SavedTick> pending = world.snapshotScheduledTicks(chunk);
        assertEquals(1, pending.size());
        assertEquals(0, pending.getFirst().remainingTicks());
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
    void restoringPersistedTickDoesNotDirtyItsChunkAgain() throws Exception {
        TestWorld world = new TestWorld();
        Chunk chunk = readyPistonChunk();
        world.install(chunk);
        chunk.markSaved(chunk.modificationEpoch());

        world.restoreScheduledBlockTick(new SavedTick("block", "voxelstories:piston",
                3, 64, 4, 7, 0, 12));

        assertFalse(chunk.isModified(),
                "das Unpack eines bereits gespeicherten Ticks darf keine neue Save-Epoch erzeugen");
        assertEquals(1, world.snapshotScheduledTicks(chunk).size());
    }

    @Test
    void restoringPersistedBlockEventDoesNotDirtyItsChunkAgain() throws Exception {
        TestWorld world = new TestWorld();
        Chunk chunk = readyPistonChunk();
        world.install(chunk);
        chunk.markSaved(chunk.modificationEpoch());

        world.restoreBlockEvent(new SavedTick("block_event", "voxelstories:piston",
                3, 64, 4, 1, 0, 0));

        assertFalse(chunk.isModified(),
                "das Unpack eines bereits gespeicherten Blockevents darf keine neue Save-Epoch erzeugen");
        assertEquals(1, world.snapshotScheduledTicks(chunk).size());
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

    @Test
    void authoritativeChunkReloadClearsRuntimeSchedulerState() throws Exception {
        TestWorld world = new TestWorld();
        Chunk chunk = readyPistonChunk();
        world.install(chunk);
        world.scheduleTick(3, 64, 4, 100);
        world.enqueueBlockEvent(3, 64, 4);
        assertEquals(2, world.snapshotScheduledTicks(chunk).size());
        /* Simuliert einen bereits geschriebenen Snapshot, damit der Test kein Welt-IO anstößt. */
        chunk.markSaved(chunk.modificationEpoch());

        world.reloadAllChunks();

        assertNull(world.loadedChunk(0, 0));
        assertNull(world.snapshotScheduledTicks(chunk));
    }

    @Test
    void largePartialUnloadAndRestorePreservesEveryScheduledTick() throws Exception {
        TestWorld world = new TestWorld();
        List<Chunk> chunks = new ArrayList<>();
        Map<Long, List<SavedTick>> persisted = new HashMap<>();
        int chunkCount = 32;
        int ticksPerChunk = 256;

        for (int chunkX = 0; chunkX < chunkCount; chunkX++) {
            Chunk chunk = tickMatrixChunk(chunkX);
            chunks.add(chunk);
            world.install(chunk);
            for (int i = 0; i < ticksPerChunk; i++) {
                int x = (chunkX << 5) + (i & 31);
                int z = i >> 5;
                world.scheduleTick(x, 64, z, 20 + i % 181);
            }
        }
        for (Chunk chunk : chunks) {
            List<SavedTick> snapshot = List.copyOf(world.snapshotScheduledTicks(chunk));
            assertEquals(ticksPerChunk, snapshot.size());
            persisted.put(Chunk.key(chunk.chunkX, chunk.chunkZ), snapshot);
            chunk.markSaved(chunk.modificationEpoch());
        }

        for (Chunk chunk : chunks) {
            if ((chunk.chunkX & 1) == 0) world.unload(chunk);
        }
        world.processUnloadedChunks();

        for (Chunk chunk : chunks) {
            List<SavedTick> current = world.snapshotScheduledTicks(chunk);
            if ((chunk.chunkX & 1) == 0) {
                assertNull(current);
            } else {
                assertEquals(persisted.get(Chunk.key(chunk.chunkX, chunk.chunkZ)), current);
            }
        }

        List<Chunk> restored = new ArrayList<>();
        for (int chunkX = 0; chunkX < chunkCount; chunkX += 2) {
            Chunk chunk = tickMatrixChunk(chunkX);
            restored.add(chunk);
            world.install(chunk);
            for (SavedTick tick : persisted.get(Chunk.key(chunkX, 0))) {
                world.restoreScheduledBlockTick(tick);
            }
        }
        for (Chunk chunk : restored) {
            assertEquals(persisted.get(Chunk.key(chunk.chunkX, chunk.chunkZ)),
                    world.snapshotScheduledTicks(chunk));
        }
    }

    @Test
    void oneWorldTickExecutesMoreThanOldFourThousandNinetySixLimit() throws Exception {
        TestWorld world = new TestWorld();
        List<Chunk> chunks = new ArrayList<>();
        int scheduled = 0;
        for (int chunkX = 0; chunkX < 5; chunkX++) {
            Chunk chunk = new Chunk(chunkX, 0);
            chunk.status = ChunkStatus.READY;
            world.install(chunk);
            chunks.add(chunk);
            for (int z = 0; z < 32 && scheduled < 5_000; z++) {
                for (int localX = 0; localX < 32 && scheduled < 5_000; localX++) {
                    chunk.setBlock(localX, 64, z, Blocks.PISTON);
                    world.scheduleTick((chunkX << 5) + localX, 64, z, 1);
                    scheduled++;
                }
            }
        }

        world.advanceGameTime();
        world.tickScheduled();

        assertEquals(5_000, scheduled);
        for (Chunk chunk : chunks) assertNull(world.snapshotScheduledTicks(chunk));
    }

    private static Chunk readyPistonChunk() {
        Chunk chunk = new Chunk(0, 0);
        chunk.status = ChunkStatus.READY;
        chunk.setBlock(3, 64, 4, Blocks.PISTON);
        return chunk;
    }

    private static Chunk tickMatrixChunk(int chunkX) {
        Chunk chunk = new Chunk(chunkX, 0);
        chunk.status = ChunkStatus.READY;
        for (int i = 0; i < 256; i++) {
            chunk.setBlock(i & 31, 64, i >> 5, Blocks.PISTON);
        }
        return chunk;
    }

    private static final class TestWorld extends Dimension {
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
                CHUNK_MANAGER_FIELD = Dimension.class.getDeclaredField("chunkManager");
                CHUNK_MANAGER_FIELD.setAccessible(true);
                GAME_TIME_FIELD = Dimension.class.getDeclaredField("gameTime");
                GAME_TIME_FIELD.setAccessible(true);
                UNLOAD_QUEUE_FIELD = ChunkManager.class.getDeclaredField("unloadAnnounceQueue");
                UNLOAD_QUEUE_FIELD.setAccessible(true);
                TICK_SCHEDULED = Dimension.class.getDeclaredMethod("tickScheduled");
                TICK_SCHEDULED.setAccessible(true);
                PROCESS_UNLOADED = Dimension.class.getDeclaredMethod("processUnloadedChunkBoundaries");
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

        Chunk loadedChunk(int chunkX, int chunkZ) throws IllegalAccessException {
            ChunkManager manager = (ChunkManager) CHUNK_MANAGER_FIELD.get(this);
            return manager.getChunk(chunkX, chunkZ);
        }

        private static LevelData level() {
            LevelData level = new LevelData();
            level.name = "scheduled-tick-persistence-test";
            level.seed = 1;

            return level;
        }
    }
}
