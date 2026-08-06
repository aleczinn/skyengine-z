package de.skyengine.game.world;

import de.skyengine.game.world.block.Blocks;
import de.skyengine.game.world.block.BlockPos;
import de.skyengine.game.world.block.Direction;
import de.skyengine.game.world.block.state.BlockState;
import de.skyengine.game.world.block.state.BlockStateCodec;
import de.skyengine.game.world.chunk.Chunk;
import de.skyengine.game.world.chunk.ChunkManager;
import de.skyengine.game.world.chunk.ChunkStatus;
import de.skyengine.game.world.save.LevelData;
import de.skyengine.test.BlocksTestBootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;

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

    @Test
    void poweredControlsNotifyTheirStrongTargetAfterRemoval() throws Exception {
        TestWorld world = new TestWorld();
        Chunk chunk = new Chunk(0, 0);
        chunk.status = ChunkStatus.READY;
        chunk.setBlock(1, 64, 1, id("skyengine:lever[face=wall,facing=east,powered=true]"));
        chunk.setBlock(3, 64, 1, id("skyengine:oak_button[face=wall,facing=east,powered=true]"));
        chunk.setBlock(5, 64, 1, id("skyengine:oak_pressure_plate[powered=true]"));
        chunk.setBlock(7, 64, 1, id("skyengine:lever[face=wall,facing=east,powered=false]"));
        world.install(chunk);

        world.setBlock(1, 64, 1, Blocks.AIR, false);
        world.setBlock(3, 64, 1, Blocks.AIR, false);
        world.setBlock(5, 64, 1, Blocks.AIR, false);
        world.setBlock(7, 64, 1, Blocks.AIR, false);

        assertEquals(List.of(
                BlockPos.asLong(0, 64, 1),
                BlockPos.asLong(2, 64, 1),
                BlockPos.asLong(5, 63, 1)), world.updatedPositions);
    }

    @Test
    void diodeFrontUpdateExcludesSourceAndUsesOnlySixGeneralUpdates() throws Exception {
        TestWorld world = new TestWorld();

        world.updateDirectionalOutputNeighbors(10, 20, 30, Direction.EAST);

        assertEquals(List.of(
                BlockPos.asLong(11, 20, 30),
                BlockPos.asLong(12, 20, 30),
                BlockPos.asLong(11, 19, 30),
                BlockPos.asLong(11, 21, 30),
                BlockPos.asLong(11, 20, 29),
                BlockPos.asLong(11, 20, 31)
        ), world.generalUpdates);
    }

    @Test
    void redstoneTorchKeepsVanillaNestedNeighborOrderAndDuplicates() throws Exception {
        TestWorld world = new TestWorld();

        world.updateGeneralNeighborsAroundAdjacentCells(10, 20, 30);

        List<Long> expected = new ArrayList<>(36);
        Direction[] outerOrder = {
                Direction.DOWN, Direction.UP, Direction.NORTH,
                Direction.SOUTH, Direction.WEST, Direction.EAST
        };
        Direction[] innerOrder = {
                Direction.WEST, Direction.EAST, Direction.DOWN,
                Direction.UP, Direction.NORTH, Direction.SOUTH
        };
        for (Direction outer : outerOrder) {
            for (Direction inner : innerOrder) {
                expected.add(BlockPos.asLong(10 + outer.offsetX() + inner.offsetX(),
                        20 + outer.offsetY() + inner.offsetY(),
                        30 + outer.offsetZ() + inner.offsetZ()));
            }
        }

        assertIterableEquals(expected, world.generalUpdates);
        assertEquals(6, world.generalUpdates.stream()
                .filter(position -> position == BlockPos.asLong(10, 20, 30)).count());
    }

    @Test
    void batchRemovalRunsPostRemovalHooksAfterAirWrite() throws Exception {
        TestWorld world = new TestWorld();
        Chunk chunk = new Chunk(0, 0);
        chunk.status = ChunkStatus.READY;
        chunk.setBlock(1, 64, 1, id("skyengine:lever[face=wall,facing=east,powered=true]"));
        world.install(chunk);

        world.breakBlocksBatch(new long[]{BlockPos.asLong(1, 64, 1)}, 1);

        assertEquals(Blocks.AIR, chunk.getBlock(1, 64, 1));
        assertEquals(List.of(BlockPos.asLong(0, 64, 1)), world.updatedPositions);
    }

    private static int id(String encoded) {
        BlockState state = BlockStateCodec.decode(encoded);
        if (state == null) throw new IllegalStateException("Test-State fehlt: " + encoded);
        return state.getId();
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
        private final List<Long> updatedPositions = new ArrayList<>();
        private final List<Long> generalUpdates = new ArrayList<>();

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
            this.updatedPositions.add(BlockPos.asLong(x, y, z));
        }

        @Override
        protected void updateGeneralStateAt(int x, int y, int z) {
            this.generalUpdates.add(BlockPos.asLong(x, y, z));
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
