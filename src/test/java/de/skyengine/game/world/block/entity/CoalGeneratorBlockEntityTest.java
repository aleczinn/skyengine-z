package de.skyengine.game.world.block.entity;

import de.skyengine.game.world.Dimension;
import de.skyengine.game.world.block.BlockPos;
import de.skyengine.game.world.block.BlockRegistry;
import de.skyengine.game.world.block.Blocks;
import de.skyengine.game.world.block.Direction;
import de.skyengine.game.world.block.Identifier;
import de.skyengine.game.world.block.state.BlockState;
import de.skyengine.game.world.item.ItemStack;
import de.skyengine.game.world.item.Items;
import de.skyengine.game.world.save.LevelData;
import de.skyengine.test.BlocksTestBootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class CoalGeneratorBlockEntityTest {
    @BeforeAll static void bootstrap() { BlocksTestBootstrap.ensureBootstrapped(); }

    @Test void solidFuelProducesEightyRfPerTickAndOnlyFrontExports() {
        TestWorld world = new TestWorld();
        world.generator.getInventory().set(0,
                new ItemStack(Items.get(Identifier.of("voxelstories:coal")), 1));
        for (int i = 0; i < 10; i++) world.generator.tick();
        assertEquals(800L, world.generator.getEnergy());
        assertTrue(world.generator.isProducing());
        assertTrue(world.generator.getCapability(Capabilities.ENERGY, Direction.NORTH).isPresent());
        assertTrue(world.generator.getCapability(Capabilities.ENERGY, Direction.SOUTH).isEmpty());
    }

    @Test void fullBufferPausesBurnTimerAndStatePersists() {
        CoalGeneratorBlockEntity generator = new CoalGeneratorBlockEntity(
                BlockEntities.COAL_GENERATOR, new BlockPos(0, 64, 0));
        DataTag initial = new DataTag().putLong("energy", CoalGeneratorBlockEntity.CAPACITY)
                .putInt("burn_time", 100).putInt("burn_duration", 1600);
        generator.load(initial);
        TestWorld world = new TestWorld(generator);
        generator.tick();
        assertEquals(100, generator.getBurnTime());
        assertFalse(generator.isProducing());

        DataTag saved = new DataTag();
        generator.save(saved);
        CoalGeneratorBlockEntity loaded = new CoalGeneratorBlockEntity(
                BlockEntities.COAL_GENERATOR, new BlockPos(0, 64, 0));
        loaded.load(saved);
        assertEquals(CoalGeneratorBlockEntity.CAPACITY, loaded.getEnergy());
        assertEquals(100, loaded.getBurnTime());
    }

    private static final class TestWorld extends Dimension {
        private BlockState state = BlockRegistry.get(Identifier.of("voxelstories:coal_generator")).getDefaultState();
        private final CoalGeneratorBlockEntity generator;
        TestWorld() { this(new CoalGeneratorBlockEntity(BlockEntities.COAL_GENERATOR, new BlockPos(0, 64, 0))); }
        TestWorld(CoalGeneratorBlockEntity generator) {
            super("__coal_generator_test", level(), null, null);
            this.generator = generator;
            this.generator.setWorld(this);
        }
        @Override public int getBlock(int x, int y, int z) { return this.state.getId(); }
        @Override public boolean setBlock(int x, int y, int z, int block, boolean updateNeighbors) {
            this.state = Blocks.getState(block); return true;
        }
        @Override public void markChunkModified(int x, int z) { }
        @Override public void updateComparatorOutputs(int x, int y, int z) { }
        private static LevelData level() { LevelData level = new LevelData(); level.name = "generator-test"; level.seed = 2; return level; }
    }
}
