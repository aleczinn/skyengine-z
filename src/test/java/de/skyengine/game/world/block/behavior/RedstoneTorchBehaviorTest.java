package de.skyengine.game.world.block.behavior;

import de.skyengine.game.world.Dimension;
import de.skyengine.game.world.block.BlockPos;
import de.skyengine.game.world.block.BlockRegistry;
import de.skyengine.game.world.block.Blocks;
import de.skyengine.game.world.block.Identifier;
import de.skyengine.game.world.block.state.AttachFace;
import de.skyengine.game.world.block.state.BlockState;
import de.skyengine.game.world.block.state.Properties;
import de.skyengine.game.world.chunk.Chunk;
import de.skyengine.game.world.chunk.ChunkManager;
import de.skyengine.game.world.chunk.ChunkStatus;
import de.skyengine.game.world.save.LevelData;
import de.skyengine.test.BlocksTestBootstrap;
import de.skyengine.utils.collect.LongIntMap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class RedstoneTorchBehaviorTest {

    @BeforeAll
    static void bootstrapBlocks() {
        BlocksTestBootstrap.ensureBootstrapped();
    }

    @Test
    void eighthRecentOffEdgeSchedulesVanillaRestart() {
        TestWorld world = new TestWorld();
        BlockState torch = state("redstone_torch")
                .with(Properties.ATTACH, AttachFace.FLOOR)
                .with(Properties.LIT, true);
        world.put(0, 64, 0, torch);

        for (int toggle = 0; toggle < 8; toggle++) {
            world.gameTime = toggle * 4L;
            world.put(0, 63, 0, state("redstone_block"));
            world.tickTorch();
            assertFalse(world.torch().get(Properties.LIT));

            if (toggle < 7) {
                world.gameTime += 2;
                world.put(0, 63, 0, state("stone"));
                world.tickTorch();
                assertTrue(world.torch().get(Properties.LIT));
            }
        }

        assertEquals(1, world.scheduledTicks);
        assertEquals(160, world.lastScheduledDelay);
    }

    @Test
    void burnoutUsesSlidingSixtyTickWindow() {
        TestWorld world = new TestWorld();
        world.put(0, 64, 0, state("redstone_torch")
                .with(Properties.ATTACH, AttachFace.FLOOR)
                .with(Properties.LIT, true));
        long[] offEdges = {0, 10, 20, 30, 40, 50, 60, 65, 70};

        for (int i = 0; i < offEdges.length; i++) {
            world.gameTime = offEdges[i];
            world.put(0, 63, 0, state("redstone_block"));
            world.tickTorch();
            if (i + 1 < offEdges.length) {
                world.gameTime++;
                world.put(0, 63, 0, state("stone"));
                world.tickTorch();
            }
        }

        assertEquals(1, world.scheduledTicks);
        assertEquals(160, world.lastScheduledDelay);
    }

    @Test
    void neighborUpdateDoesNotScheduleBehindTickAlreadyCollectedThisRound() {
        TestWorld world = new TestWorld();
        BlockState torch = state("redstone_torch")
                .with(Properties.ATTACH, AttachFace.FLOOR)
                .with(Properties.LIT, true);
        world.put(0, 64, 0, torch);
        world.put(0, 63, 0, state("redstone_block"));
        world.willTickThisTick = true;

        torch.getBlock().getStateForNeighborUpdate(world, 0, 64, 0, torch);

        assertEquals(0, world.scheduledTicks);
    }

    @Test
    void stateChangeUsesFlagThreeUpdatesBeforeTorchSpecificNeighborRings() {
        TestWorld world = new TestWorld();
        world.put(0, 64, 0, state("redstone_torch")
                .with(Properties.ATTACH, AttachFace.FLOOR)
                .with(Properties.LIT, true));
        world.put(0, 63, 0, state("redstone_block"));

        world.tickTorch();

        assertFalse(world.torch().get(Properties.LIT));
        assertTrue(world.lastSetBlockUpdatedNeighbors,
                "Vanillas Flag 3 muss den direkt angrenzenden Staub aktualisieren");
        assertEquals(1, world.torchNeighborRings,
                "der zusaetzliche RedstoneTorchBlock.notifyNeighbors-Pfad darf nicht entfallen");
    }

    private static BlockState state(String path) {
        var block = BlockRegistry.get(Identifier.of("skyengine:" + path));
        if (block == null) throw new IllegalStateException("Testblock fehlt: " + path);
        return block.getDefaultState();
    }

    private static final class TestWorld extends Dimension {
        private static final Field CHUNKS_FIELD;

        static {
            try {
                CHUNKS_FIELD = ChunkManager.class.getDeclaredField("chunks");
                CHUNKS_FIELD.setAccessible(true);
            } catch (ReflectiveOperationException e) {
                throw new ExceptionInInitializerError(e);
            }
        }

        private final LongIntMap blocks = new LongIntMap(8);
        private long gameTime;
        private int scheduledTicks;
        private int lastScheduledDelay;
        private boolean willTickThisTick;
        private boolean lastSetBlockUpdatedNeighbors;
        private int torchNeighborRings;

        TestWorld() {
            super("__redstone_torch_test", level(), null, null);
            try {
                Field managerField = Dimension.class.getDeclaredField("chunkManager");
                managerField.setAccessible(true);
                ChunkManager manager = (ChunkManager) managerField.get(this);
                Chunk chunk = new Chunk(0, 0);
                chunk.status = ChunkStatus.READY;
                @SuppressWarnings("unchecked")
                Map<Long, Chunk> chunks = (Map<Long, Chunk>) CHUNKS_FIELD.get(manager);
                chunks.put(Chunk.key(0, 0), chunk);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
        }

        void put(int x, int y, int z, BlockState state) {
            this.blocks.put(BlockPos.asLong(x, y, z), state.getId());
        }

        BlockState torch() {
            return Blocks.getState(this.getBlock(0, 64, 0));
        }

        void tickTorch() {
            BlockState state = this.torch();
            state.getBlock().scheduledTick(this, 0, 64, 0, state);
        }

        @Override
        public long getGameTime() {
            return this.gameTime;
        }

        @Override
        public int getBlock(int x, int y, int z) {
            return this.blocks.getOrDefault(BlockPos.asLong(x, y, z), Blocks.AIR);
        }

        @Override
        public boolean setBlock(int x, int y, int z, int block, boolean updateNeighbors) {
            this.blocks.put(BlockPos.asLong(x, y, z), block);
            this.lastSetBlockUpdatedNeighbors = updateNeighbors;
            return true;
        }

        @Override
        public void updateGeneralNeighborsAroundAdjacentCells(int x, int y, int z) {
            this.torchNeighborRings++;
        }

        @Override
        public void scheduleTick(int x, int y, int z, int delayTicks) {
            this.scheduledTicks++;
            this.lastScheduledDelay = delayTicks;
        }

        @Override
        public boolean willTickThisTick(int x, int y, int z) {
            return this.willTickThisTick;
        }

        private static LevelData level() {
            LevelData level = new LevelData();
            level.name = "redstone-torch-test";
            level.seed = 1;
            level.worldType = "imported";
            return level;
        }
    }
}
