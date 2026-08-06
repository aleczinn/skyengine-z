package de.skyengine.game.world;

import de.skyengine.game.world.block.Blocks;
import de.skyengine.game.world.block.Direction;
import de.skyengine.game.world.chunk.Chunk;
import de.skyengine.game.world.chunk.ChunkManager;
import de.skyengine.game.world.chunk.ChunkStatus;
import de.skyengine.game.world.save.LevelData;
import de.skyengine.test.BlocksTestBootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

final class WorldSetBlockTest {

    @BeforeAll
    static void bootstrapBlocks() {
        BlocksTestBootstrap.ensureBootstrapped();
    }

    @Test
    void identicalStateDoesNotNotifyNeighbors() throws Exception {
        TestWorld world = new TestWorld();
        Chunk chunk = new Chunk(0, 0);
        chunk.status = ChunkStatus.READY;
        chunk.setBlock(1, 64, 1, Blocks.STONE);
        world.install(chunk);

        assertFalse(world.setBlock(1, 64, 1, Blocks.STONE, true));
        assertEquals(0, world.neighborUpdates);
        assertEquals(Blocks.STONE, chunk.getBlock(1, 64, 1));
    }

    @Test
    void vanillaUsesDifferentOrdersForNeighborAndShapeUpdates() {
        assertArrayEquals(new Direction[] {
                Direction.WEST, Direction.EAST, Direction.DOWN,
                Direction.UP, Direction.NORTH, Direction.SOUTH
        }, Direction.neighborUpdateValues());
        assertArrayEquals(new Direction[] {
                Direction.WEST, Direction.EAST, Direction.NORTH,
                Direction.SOUTH, Direction.DOWN, Direction.UP
        }, Direction.shapeUpdateValues());
    }

    private static final class TestWorld extends World {
        private static final Field CHUNKS_FIELD;

        static {
            try {
                CHUNKS_FIELD = ChunkManager.class.getDeclaredField("chunks");
                CHUNKS_FIELD.setAccessible(true);
            } catch (ReflectiveOperationException e) {
                throw new ExceptionInInitializerError(e);
            }
        }

        private final ChunkManager manager;
        private int neighborUpdates;

        TestWorld() throws ReflectiveOperationException {
            super("__set_block_test", level(), null, null);
            Field field = World.class.getDeclaredField("chunkManager");
            field.setAccessible(true);
            this.manager = (ChunkManager) field.get(this);
        }

        @SuppressWarnings("unchecked")
        void install(Chunk chunk) throws IllegalAccessException {
            ((Map<Long, Chunk>) CHUNKS_FIELD.get(this.manager))
                    .put(Chunk.key(chunk.chunkX, chunk.chunkZ), chunk);
        }

        @Override
        public void updateNeighbors(int x, int y, int z) {
            this.neighborUpdates++;
        }

        private static LevelData level() {
            LevelData level = new LevelData();
            level.name = "set-block-test";
            level.seed = 1;
            level.worldType = "imported";
            return level;
        }
    }
}
