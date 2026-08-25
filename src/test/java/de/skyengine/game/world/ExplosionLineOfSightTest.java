package de.skyengine.game.world;

import de.skyengine.game.world.block.Blocks;
import de.skyengine.game.world.block.shape.BlockShape;
import de.skyengine.game.world.save.LevelData;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExplosionLineOfSightTest {

    @Test
    void thinShapeBetweenOldSamplePointsStillBlocksRay() {
        TestWorld world = new TestWorld(BlockShape.box(0.61, 0.0, 0.0, 0.62, 1.0, 1.0));

        boolean visible = Explosion.hasLineOfSight(world,
                -1.0, 0.5, 0.5, 2.0, 0.5, 0.5, new Dimension.ChunkMemo());

        assertFalse(visible);
    }

    @Test
    void rayThroughEmptyPartOfPartialShapeRemainsVisible() {
        TestWorld world = new TestWorld(BlockShape.box(0.0, 0.5, 0.0, 1.0, 1.0, 1.0));

        boolean visible = Explosion.hasLineOfSight(world,
                -1.0, 0.25, 0.5, 2.0, 0.25, 0.5, new Dimension.ChunkMemo());

        assertTrue(visible);
    }

    private static final class TestWorld extends Dimension {
        private final BlockShape shape;

        private TestWorld(BlockShape shape) {
            super("__explosion_los_test", level(), null, null);
            this.shape = shape;
        }

        @Override
        int getBlockMemo(int x, int y, int z, ChunkMemo memo) {
            return x == 0 && y == 0 && z == 0 ? Blocks.AIR + 1 : Blocks.AIR;
        }

        @Override
        public BlockShape getCollisionShape(int x, int y, int z) {
            return x == 0 && y == 0 && z == 0 ? this.shape : BlockShape.EMPTY;
        }

        private static LevelData level() {
            LevelData level = new LevelData();
            level.name = "explosion-los-test";
            level.seed = 1;
            level.worldType = "imported";
            return level;
        }
    }
}
