package de.skyengine.game.entity;

import de.skyengine.game.physics.AABB;
import de.skyengine.game.world.World;
import de.skyengine.game.world.save.LevelData;
import de.skyengine.test.BlocksTestBootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class PrimedTntEntityTest {

    @BeforeAll
    static void bootstrapBlocks() {
        BlocksTestBootstrap.ensureBootstrapped();
    }

    @Test
    void explosionOriginUsesOneSixteenthOfEntityHeight() {
        PrimedTntEntity tnt = new PrimedTntEntity(4.0F, 80);
        tnt.setPosition(2.5, 64.0, -3.5);

        assertEquals(64.06125, tnt.explosionY(), 2.0E-9);
    }

    @Test
    void primingUsesVanillasCircularInitialImpulse() {
        TestWorld world = new TestWorld();

        world.spawnPrimedTnt(0.5, 64.0, 0.5, 4.0F, 80);

        PrimedTntEntity tnt = assertInstanceOf(PrimedTntEntity.class, world.spawned);
        assertEquals(0.02, Math.hypot(tnt.motionX, tnt.motionZ), 1.0E-12);
        assertEquals(0.20000000298023224, tnt.motionY, 0.0);
    }

    @Test
    void airborneTickAppliesGravityBeforeMoveAndDragAfterwards() {
        TestWorld world = new TestWorld();
        PrimedTntEntity tnt = new PrimedTntEntity(4.0F, 80);
        tnt.setPosition(0.5, 64.0, 0.5);
        tnt.motionX = 0.4;
        tnt.motionY = 0.2;
        tnt.motionZ = -0.3;

        tnt.tick(world);

        assertEquals(0.9, tnt.x, 1.0E-12);
        assertEquals(64.16, tnt.y, 1.0E-12);
        assertEquals(0.2, tnt.z, 1.0E-12);
        assertEquals(0.4 * 0.98, tnt.motionX, 1.0E-12);
        assertEquals((0.2 - 0.04) * 0.98, tnt.motionY, 1.0E-12);
        assertEquals(-0.3 * 0.98, tnt.motionZ, 1.0E-12);
    }

    @Test
    void groundedTickAppliesAirDragBeforeGroundMultiplier() {
        TestWorld world = new TestWorld();
        PrimedTntEntity tnt = new GroundedTnt();
        tnt.setPosition(0.5, 64.0, 0.5);
        tnt.motionX = 0.4;
        tnt.motionY = 0.2;
        tnt.motionZ = -0.3;

        tnt.tick(world);

        assertEquals(0.4 * 0.98 * 0.7, tnt.motionX, 1.0E-12);
        assertEquals((0.2 - 0.04) * 0.98 * -0.5, tnt.motionY, 1.0E-12);
        assertEquals(-0.3 * 0.98 * 0.7, tnt.motionZ, 1.0E-12);
    }

    private static final class GroundedTnt extends PrimedTntEntity {
        private GroundedTnt() {
            super(4.0F, 80);
        }

        @Override
        public void move(World world, double dx, double dy, double dz) {
            this.onGround = true;
        }
    }

    private static final class TestWorld extends World {
        private Entity spawned;

        private TestWorld() {
            super("__primed_tnt_test", level(), null, null);
        }

        @Override
        public void spawnEntity(Entity entity) {
            this.spawned = entity;
        }

        @Override
        public int getBlock(int x, int y, int z) {
            return de.skyengine.game.world.block.Blocks.AIR;
        }

        @Override
        public List<AABB> getCollisionBoxes(AABB area) {
            return List.of();
        }

        private static LevelData level() {
            LevelData level = new LevelData();
            level.name = "primed-tnt-test";
            level.seed = 1;
            level.worldType = "imported";
            return level;
        }
    }
}
