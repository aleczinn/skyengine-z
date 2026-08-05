package de.skyengine.game.world;

import de.skyengine.game.world.block.Blocks;
import de.skyengine.game.world.chunk.Chunk;
import de.skyengine.game.world.chunk.ChunkManager;
import de.skyengine.game.world.chunk.ChunkStatus;
import de.skyengine.game.world.save.LevelData;
import de.skyengine.test.BlocksTestBootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class WorldDeferredStateUpdateTest {

    @BeforeAll
    static void bootstrapBlocks() {
        BlocksTestBootstrap.ensureBootstrapped();
    }

    @Test
    void nonReadyUpdateSleepsUntilTheSameChunkBecomesReady() throws Exception {
        TestWorld world = new TestWorld();
        Chunk chunk = chunk(0, 0, ChunkStatus.LIT);
        world.install(chunk);

        world.updateBlockStateAt(1, 64, 1);
        assertEquals(1, world.parkedCount());
        assertEquals(0, world.dueCount());

        /* Ein unfertiger Chunk darf im Steady-State keine erneute Arbeit pro Tick erzeugen. */
        for (int i = 0; i < 5; i++) world.processDeferredStateUpdates();
        assertEquals(1, world.parkedCount());
        assertEquals(0, world.dueCount());

        chunk.status = ChunkStatus.READY;
        world.manager.requeueReadyAnnounce(chunk);
        world.processReadyChunks();
        assertEquals(0, world.parkedCount());
        assertEquals(1, world.dueCount());

        world.processDeferredStateUpdates();
        assertEquals(0, world.dueCount());
    }

    @Test
    void replacementChunkDoesNotInheritParkedUpdates() throws Exception {
        TestWorld world = new TestWorld();
        Chunk oldChunk = chunk(2, -3, ChunkStatus.LIT);
        world.install(oldChunk);
        world.updateBlockStateAt(65, 64, -95);
        assertEquals(1, world.parkedCount());

        Chunk replacement = chunk(2, -3, ChunkStatus.READY);
        world.install(replacement);
        world.manager.requeueReadyAnnounce(replacement);
        world.processReadyChunks();

        assertEquals(0, world.parkedCount());
        assertEquals(0, world.dueCount());
    }

    @Test
    void replacementChunkDoesNotInheritNextTickUpdates() throws Exception {
        TestWorld world = new TestWorld();
        Chunk oldChunk = chunk(4, 2, ChunkStatus.READY);
        world.install(oldChunk);
        world.deferBlockUpdate(129, 64, 65);
        assertEquals(1, world.dueCount());

        world.install(chunk(4, 2, ChunkStatus.READY));
        world.processDeferredStateUpdates();

        assertEquals(0, world.dueCount());
    }

    @Test
    void unloadedChunkDropsItsParkedGroupAfterRemovalVersionChanges() throws Exception {
        TestWorld world = new TestWorld();
        Chunk chunk = chunk(-1, 1, ChunkStatus.LIT);
        world.install(chunk);
        world.updateBlockStateAt(-1, 64, 32);
        assertEquals(1, world.parkedCount());

        world.removeAndBumpVersion(chunk);
        world.processDeferredStateUpdates();

        assertEquals(0, world.parkedCount());
        assertEquals(0, world.dueCount());
    }

    @Test
    void deferredWorkIsCappedPerTickAndKeepsFifoRemainder() throws Exception {
        TestWorld world = new TestWorld();
        world.install(chunk(0, 0, ChunkStatus.READY));

        for (int i = 0; i < 600; i++) {
            world.deferBlockUpdate(i & 31, i >> 5, 0);
        }
        world.processDeferredStateUpdates();

        assertEquals(88, world.dueCount());
        world.processDeferredStateUpdates();
        assertEquals(0, world.dueCount());
    }

    private static Chunk chunk(int x, int z, ChunkStatus status) {
        Chunk chunk = new Chunk(x, z);
        chunk.status = status;
        return chunk;
    }

    private static final class TestWorld extends World {
        private static final Field CHUNKS_FIELD;
        private static final Field REMOVAL_VERSION_FIELD;
        private static final Field DEFERRED_FIELD;
        private static final Field PARKED_COUNT_FIELD;
        private static final Method PROCESS_READY;
        private static final Method PROCESS_DEFERRED;

        static {
            try {
                CHUNKS_FIELD = ChunkManager.class.getDeclaredField("chunks");
                CHUNKS_FIELD.setAccessible(true);
                REMOVAL_VERSION_FIELD = ChunkManager.class.getDeclaredField("chunkRemovalVersion");
                REMOVAL_VERSION_FIELD.setAccessible(true);
                DEFERRED_FIELD = World.class.getDeclaredField("deferredStateUpdates");
                DEFERRED_FIELD.setAccessible(true);
                PARKED_COUNT_FIELD = World.class.getDeclaredField("parkedStateUpdateCount");
                PARKED_COUNT_FIELD.setAccessible(true);
                PROCESS_READY = World.class.getDeclaredMethod("processReadyChunks");
                PROCESS_READY.setAccessible(true);
                PROCESS_DEFERRED = World.class.getDeclaredMethod("processDeferredStateUpdates");
                PROCESS_DEFERRED.setAccessible(true);
            } catch (ReflectiveOperationException e) {
                throw new ExceptionInInitializerError(e);
            }
        }

        private final ChunkManager manager;

        TestWorld() throws ReflectiveOperationException {
            super("__deferred_state_update_test", level(), null, null);
            Field field = World.class.getDeclaredField("chunkManager");
            field.setAccessible(true);
            this.manager = (ChunkManager) field.get(this);
        }

        @SuppressWarnings("unchecked")
        void install(Chunk chunk) throws IllegalAccessException {
            ((Map<Long, Chunk>) CHUNKS_FIELD.get(this.manager))
                    .put(Chunk.key(chunk.chunkX, chunk.chunkZ), chunk);
        }

        @SuppressWarnings("unchecked")
        void removeAndBumpVersion(Chunk chunk) throws IllegalAccessException {
            ((Map<Long, Chunk>) CHUNKS_FIELD.get(this.manager))
                    .remove(Chunk.key(chunk.chunkX, chunk.chunkZ));
            REMOVAL_VERSION_FIELD.setInt(this.manager, this.manager.getChunkRemovalVersion() + 1);
        }

        int dueCount() throws IllegalAccessException {
            return ((Map<?, ?>) DEFERRED_FIELD.get(this)).size();
        }

        int parkedCount() throws IllegalAccessException {
            return PARKED_COUNT_FIELD.getInt(this);
        }

        void processReadyChunks() throws ReflectiveOperationException {
            PROCESS_READY.invoke(this);
        }

        void processDeferredStateUpdates() throws ReflectiveOperationException {
            PROCESS_DEFERRED.invoke(this);
        }

        private static LevelData level() {
            LevelData level = new LevelData();
            level.name = "deferred-state-update-test";
            level.seed = 1;
            level.worldType = "imported";
            return level;
        }
    }
}
