package de.skyengine.game.world.block.entity;

import de.skyengine.game.entity.Entity;
import de.skyengine.game.physics.AABB;
import de.skyengine.game.world.World;
import de.skyengine.game.world.block.BlockPos;
import de.skyengine.game.world.block.BlockRegistry;
import de.skyengine.game.world.block.Blocks;
import de.skyengine.game.world.block.Direction;
import de.skyengine.game.world.block.Identifier;
import de.skyengine.game.world.block.state.BlockState;
import de.skyengine.game.world.save.LevelData;
import de.skyengine.test.BlocksTestBootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class PistonMovingBlockEntityTest {

    @BeforeAll
    static void bootstrapBlocks() {
        BlocksTestBootstrap.ensureBootstrapped();
    }

    @Test
    void movingSlabUsesItsRealCollisionShape() {
        TestWorld world = new TestWorld();
        Entity entity = world.addEntity(0.2, 64.75, 0.5);
        PistonMovingBlockEntity moving = world.moving(state("stone_slab"), Direction.EAST);

        moving.tick();

        assertEquals(0.2, entity.x, 1.0E-9);
    }

    @Test
    void movingFullBlockPushesBySweptPenetration() {
        TestWorld world = new TestWorld();
        Entity entity = world.addEntity(0.2, 64.1, 0.5);
        PistonMovingBlockEntity moving = world.moving(state("stone"), Direction.EAST);

        moving.tick();

        assertTrue(entity.x > 0.7, "Vollblock muss die Entity vor seiner Bewegungsfläche herschieben");
    }

    @Test
    void horizontallyMovingHoneyCarriesStandingEntity() {
        TestWorld world = new TestWorld();
        Entity entity = world.addEntity(-0.5, 64.9375, 0.5);
        entity.onGround = true;
        PistonMovingBlockEntity moving = world.moving(state("honey_block"), Direction.EAST);

        moving.tick();

        assertEquals(0.0, entity.x, 1.0E-9);
    }

    @Test
    void honeyCarriesEntitySupportedAcrossItsEdge() {
        TestWorld world = new TestWorld();
        Entity entity = world.addEntity(0.1, 64.9375, 0.5);
        entity.onGround = true;
        PistonMovingBlockEntity moving = world.moving(state("honey_block"), Direction.EAST);

        moving.tick();

        assertEquals(0.6, entity.x, 1.0E-9);
    }

    private static BlockState state(String path) {
        var block = BlockRegistry.get(Identifier.of("skyengine:" + path));
        if (block == null) throw new IllegalStateException("Testblock fehlt: " + path);
        return block.getDefaultState();
    }

    private static final class TestWorld extends World {
        private final List<Entity> entities = new ArrayList<>();

        private TestWorld() {
            super("__piston_moving_test", level(), null, null);
        }

        private Entity addEntity(double x, double y, double z) {
            Entity entity = new Entity() {};
            entity.setPosition(x, y, z);
            this.entities.add(entity);
            return entity;
        }

        private PistonMovingBlockEntity moving(BlockState movedState, Direction direction) {
            PistonMovingBlockEntity moving = new PistonMovingBlockEntity(
                    BlockEntities.PISTON_MOVING, new BlockPos(0, 64, 0));
            moving.setWorld(this);
            moving.configure(movedState.getId(), direction, true, false, false);
            return moving;
        }

        @Override
        public int getBlock(int x, int y, int z) {
            return x == 0 && y == 64 && z == 0 ? Blocks.MOVING_PISTON : Blocks.AIR;
        }

        @Override
        public List<AABB> getCollisionBoxes(AABB area) {
            return List.of();
        }

        @Override
        public void forEachEntityNearby(double x, double z, int chunkRadius, Consumer<Entity> action) {
            for (Entity entity : this.entities) action.accept(entity);
        }

        @Override
        public void markChunkModified(int x, int z) {
        }

        private static LevelData level() {
            LevelData level = new LevelData();
            level.name = "piston-moving-test";
            level.seed = 1;
            level.worldType = "imported";
            return level;
        }
    }
}
