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
        private static final Method TICK_SCHEDULED;

        static {
            try {
                CHUNKS_FIELD = ChunkManager.class.getDeclaredField("chunks");
                CHUNKS_FIELD.setAccessible(true);
                CHUNK_MANAGER_FIELD = World.class.getDeclaredField("chunkManager");
                CHUNK_MANAGER_FIELD.setAccessible(true);
                GAME_TIME_FIELD = World.class.getDeclaredField("gameTime");
                GAME_TIME_FIELD.setAccessible(true);
                TICK_SCHEDULED = World.class.getDeclaredMethod("tickScheduled");
                TICK_SCHEDULED.setAccessible(true);
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

        private static LevelData level() {
            LevelData level = new LevelData();
            level.name = "scheduled-tick-persistence-test";
            level.seed = 1;
            level.worldType = "imported";
            return level;
        }
    }
}
