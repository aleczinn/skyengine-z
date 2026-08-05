package de.skyengine.game.world;

import de.skyengine.game.world.block.Blocks;
import de.skyengine.game.world.block.state.BlockState;
import de.skyengine.game.world.block.state.BlockStateCodec;
import de.skyengine.game.world.block.state.Properties;
import de.skyengine.game.world.chunk.Chunk;
import de.skyengine.game.world.chunk.ChunkManager;
import de.skyengine.game.world.chunk.ChunkSection;
import de.skyengine.game.world.chunk.ChunkStatus;
import de.skyengine.game.world.save.LevelData;
import de.skyengine.test.BlocksTestBootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class WorldChunkRedstoneReconciliationTest {

    @BeforeAll
    static void bootstrapBlocks() {
        BlocksTestBootstrap.ensureBootstrapped();
    }

    @Test
    void readyChunkReconcilesWireAndStrongPowerAcrossItsLoadedSeam() throws Exception {
        TestWorld world = new TestWorld();
        Chunk west = readyChunk(0, 0);
        Chunk east = readyChunk(1, 0);
        world.install(west);
        world.install(east);
        west.loadSeeded = true;

        int wire = id("skyengine:redstone_wire[east=none,north=none,power=0,south=none,west=none]");
        west.setBlock(ChunkSection.MASK, 64, 8, wire);
        east.setBlock(0, 64, 8, wire);
        east.setBlock(1, 64, 8, id("skyengine:redstone_block"));

        /* Der Hebel speist den Stein an der Kante stark; die Lampe liegt eine weitere Zelle
           im neuen Chunk. Damit beweist der Test das erforderliche Zwei-Zellen-Band. */
        west.setBlock(ChunkSection.MASK, 64, 12,
                id("skyengine:lever[face=wall,facing=west,powered=true]"));
        east.setBlock(0, 64, 12, id("skyengine:stone"));
        east.setBlock(1, 64, 12, id("skyengine:redstone_lamp[lit=false]"));

        world.manager.requeueReadyAnnounce(east);
        world.processReadyChunks();

        BlockState westWire = Blocks.getState(west.getBlock(ChunkSection.MASK, 64, 8));
        BlockState eastWire = Blocks.getState(east.getBlock(0, 64, 8));
        assertTrue(westWire.get(Properties.WIRE_EAST).isConnected());
        assertTrue(eastWire.get(Properties.WIRE_WEST).isConnected());
        assertTrue(westWire.get(Properties.POWER) > 0);
        assertTrue(Blocks.getState(east.getBlock(1, 64, 12)).get(Properties.LIT));
        assertTrue(east.loadSeeded);

        /* Während eines Remeshs ist der verbleibende Chunk LIT und noch nicht editierbar.
           Der Unload muss die offene Kante daher bis zu dessen nächstem READY parken. */
        west.status = ChunkStatus.LIT;
        world.unload(east);
        world.processUnloadedChunkBoundaries();
        assertTrue(west.pendingRedstoneBoundaryMask != 0);
        assertTrue(Blocks.getState(west.getBlock(ChunkSection.MASK, 64, 8))
                .get(Properties.POWER) > 0);

        west.status = ChunkStatus.READY;
        world.manager.requeueReadyAnnounce(west);
        world.processReadyChunks();
        assertEquals(0, Blocks.getState(west.getBlock(ChunkSection.MASK, 64, 8))
                .get(Properties.POWER));
        assertEquals(0, west.pendingRedstoneBoundaryMask);
    }

    private static Chunk readyChunk(int x, int z) {
        Chunk chunk = new Chunk(x, z);
        chunk.status = ChunkStatus.READY;
        return chunk;
    }

    private static int id(String encoded) {
        BlockState state = BlockStateCodec.decode(encoded);
        if (state == null) throw new IllegalStateException("Test-State fehlt: " + encoded);
        return state.getId();
    }

    private static final class TestWorld extends World {
        private static final Field CHUNKS_FIELD;
        private static final Field UNLOAD_QUEUE_FIELD;
        private static final Method PROCESS_READY;
        private static final Method PROCESS_UNLOADED;

        static {
            try {
                CHUNKS_FIELD = ChunkManager.class.getDeclaredField("chunks");
                CHUNKS_FIELD.setAccessible(true);
                UNLOAD_QUEUE_FIELD = ChunkManager.class.getDeclaredField("unloadAnnounceQueue");
                UNLOAD_QUEUE_FIELD.setAccessible(true);
                PROCESS_READY = World.class.getDeclaredMethod("processReadyChunks");
                PROCESS_READY.setAccessible(true);
                PROCESS_UNLOADED = World.class.getDeclaredMethod("processUnloadedChunkBoundaries");
                PROCESS_UNLOADED.setAccessible(true);
            } catch (ReflectiveOperationException e) {
                throw new ExceptionInInitializerError(e);
            }
        }

        private final ChunkManager manager;

        TestWorld() throws ReflectiveOperationException {
            super("__chunk_redstone_test", level(), null, null);
            Field field = World.class.getDeclaredField("chunkManager");
            field.setAccessible(true);
            this.manager = (ChunkManager) field.get(this);
        }

        @SuppressWarnings("unchecked")
        void install(Chunk chunk) throws IllegalAccessException {
            ((Map<Long, Chunk>) CHUNKS_FIELD.get(this.manager))
                    .put(Chunk.key(chunk.chunkX, chunk.chunkZ), chunk);
        }

        void processReadyChunks() throws ReflectiveOperationException {
            PROCESS_READY.invoke(this);
        }

        @SuppressWarnings("unchecked")
        void unload(Chunk chunk) throws IllegalAccessException {
            ((Map<Long, Chunk>) CHUNKS_FIELD.get(this.manager))
                    .remove(Chunk.key(chunk.chunkX, chunk.chunkZ));
            ((ConcurrentLinkedQueue<Long>) UNLOAD_QUEUE_FIELD.get(this.manager))
                    .add(Chunk.key(chunk.chunkX, chunk.chunkZ));
        }

        void processUnloadedChunkBoundaries() throws ReflectiveOperationException {
            PROCESS_UNLOADED.invoke(this);
        }

        @Override
        public boolean setBlock(int x, int y, int z, int block, boolean updateNeighbors) {
            Chunk chunk = this.manager.getChunk(x >> ChunkSection.SHIFT, z >> ChunkSection.SHIFT);
            if (chunk == null || chunk.status != ChunkStatus.READY) return false;
            chunk.setBlock(x & ChunkSection.MASK, y, z & ChunkSection.MASK, block);
            return true;
        }

        private static LevelData level() {
            LevelData level = new LevelData();
            level.name = "chunk-redstone-test";
            level.seed = 1;
            level.worldType = "imported";
            return level;
        }
    }
}
