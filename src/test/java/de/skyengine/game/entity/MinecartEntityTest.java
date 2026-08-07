package de.skyengine.game.entity;

import de.skyengine.game.world.World;
import de.skyengine.game.world.block.BlockPos;
import de.skyengine.game.world.block.BlockRegistry;
import de.skyengine.game.world.block.Blocks;
import de.skyengine.game.world.block.Identifier;
import de.skyengine.game.world.block.state.BlockState;
import de.skyengine.game.world.block.state.Properties;
import de.skyengine.game.world.block.state.RailShape;
import de.skyengine.game.world.save.LevelData;
import de.skyengine.game.physics.AABB;
import de.skyengine.test.BlocksTestBootstrap;
import de.skyengine.utils.collect.LongIntMap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.util.List;

final class MinecartEntityTest {

    @BeforeAll
    static void bootstrapBlocks() { BlocksTestBootstrap.ensureBootstrapped(); }

    @Test
    void cartStaysCenteredOnStraightRailAndPoweredRailAccelerates() {
        TestWorld world = new TestWorld();
        world.put(0, 64, 0, rail("powered_rail", true));
        world.put(1, 64, 0, rail("powered_rail", true));
        MinecartEntity cart = new MinecartEntity();
        cart.setPosition(0.5, 64.0625, 0.5);
        cart.motionX = 0.1;

        cart.tick(world);

        assertTrue(cart.x > 0.6);
        assertEquals(0.5, cart.z, 1.0E-6);
        assertTrue(cart.motionX > 0.1);
        assertEquals(0, cart.motionY, 1.0E-9);
    }

    @Test
    void freshlyPlacedCartAlignsWithStraightRailAndCurve() {
        TestWorld world = new TestWorld();
        MinecartEntity cart = new MinecartEntity();

        BlockState normalRail = BlockRegistry.get(Identifier.of("skyengine:rail")).getDefaultState();
        world.put(0, 64, 0, normalRail
                .with(Properties.RAIL_SHAPE, RailShape.EAST_WEST));
        cart.setPosition(0.5, 64.0625, 0.5);
        cart.alignToRail(world);
        assertEquals(90, cart.yaw, 1.0E-6);

        world.put(0, 64, 0, normalRail
                .with(Properties.RAIL_SHAPE, RailShape.SOUTH_EAST));
        cart.alignToRail(world);
        assertEquals(45, cart.yaw, 1.0E-6);
    }

    @Test
    void playerCanMountAndIsCarriedByCart() {
        MinecartEntity cart = new MinecartEntity();
        cart.setPosition(2.5, 64.0625, 3.5);
        EntityPlayer player = new EntityPlayer();
        player.setPosition(2, 64, 3);

        assertTrue(cart.interact(player));
        assertEquals(cart, player.getVehicle());
        assertEquals(cart.x, player.x);
        assertEquals(cart.y - 0.35, player.y, 1.0E-9);
        assertEquals(cart.z, player.z);

        EntityPlayer second = new EntityPlayer();
        assertFalse(cart.interact(second));
        player.stopRiding(new TestWorld());
        assertNull(player.getVehicle());
        assertFalse(cart.hasPassengers());
    }

    @Test
    void walkingPlayerPushesEmptyMinecartAway() {
        MinecartEntity cart = new MinecartEntity();
        cart.setPosition(0.5, 64, 0.5);
        EntityPlayer player = new EntityPlayer();
        player.setPosition(0.9, 64, 0.5);

        cart.pushFrom(player);

        assertTrue(cart.motionX < 0, "Minecart muss sich vom Spieler wegbewegen");
        assertTrue(player.motionX > 0, "Spieler muss den entgegengesetzten Kontaktimpuls erhalten");
    }

    @Test
    void passengerDoesNotPushOwnMinecart() {
        MinecartEntity cart = new MinecartEntity();
        cart.setPosition(0.5, 64, 0.5);
        EntityPlayer player = new EntityPlayer();
        player.setPosition(0.9, 64, 0.5);
        assertTrue(cart.interact(player));

        cart.pushFrom(player);

        assertEquals(0, cart.motionX, 1.0E-9);
        assertEquals(0, cart.motionZ, 1.0E-9);
    }

    @Test
    void passengerImpulseStartsCartGraduallyInEitherRailDirection() {
        TestWorld world = new TestWorld();
        world.put(0, 64, 0, BlockRegistry.get(Identifier.of("skyengine:rail")).getDefaultState()
                .with(Properties.RAIL_SHAPE, RailShape.EAST_WEST));

        MinecartEntity eastbound = new MinecartEntity();
        eastbound.setPosition(0.5, 64.0625, 0.5);
        eastbound.addPassengerImpulse(0.001, 0);
        eastbound.tick(world);
        assertTrue(eastbound.motionX > 0);
        assertTrue(eastbound.motionX < 0.002, "Fahrerimpuls darf nicht auf Fahrtempo springen");

        MinecartEntity westbound = new MinecartEntity();
        westbound.setPosition(0.5, 64.0625, 0.5);
        westbound.addPassengerImpulse(-0.001, 0);
        westbound.tick(world);
        assertTrue(westbound.motionX < 0, "Anrollen muss in beide Schienenrichtungen gehen");
    }

    @Test
    void cartGainsHeightWhileClimbingAscendingRail() {
        TestWorld world = new TestWorld();
        BlockState slope = BlockRegistry.get(Identifier.of("skyengine:rail")).getDefaultState()
                .with(Properties.RAIL_SHAPE, RailShape.ASCENDING_EAST);
        world.put(0, 64, 0, slope);
        world.put(1, 65, 0, BlockRegistry.get(Identifier.of("skyengine:rail")).getDefaultState()
                .with(Properties.RAIL_SHAPE, RailShape.EAST_WEST));
        MinecartEntity cart = new MinecartEntity();
        cart.setPosition(0.25, 64.3125, 0.5);
        cart.motionX = 0.2;

        cart.tick(world);

        assertTrue(cart.x > 0.25);
        assertTrue(cart.y > 64.3125);
        assertTrue(cart.pitch > 0);
    }

    @Test
    void cartClimbsOverUpperRailSupportInsteadOfCollidingWithIt() {
        TestWorld world = new TestWorld() {
            @Override public List<AABB> getCollisionBoxes(AABB area) {
                AABB upperSupport = new AABB(1, 64, 0, 2, 65, 1);
                return area.intersects(upperSupport) ? List.of(upperSupport) : List.of();
            }
        };
        world.put(0, 64, 0, BlockRegistry.get(Identifier.of("skyengine:rail")).getDefaultState()
                .with(Properties.RAIL_SHAPE, RailShape.ASCENDING_EAST));
        world.put(1, 64, 0, BlockRegistry.get(Identifier.of("skyengine:stone")).getDefaultState());
        world.put(1, 65, 0, BlockRegistry.get(Identifier.of("skyengine:rail")).getDefaultState()
                .with(Properties.RAIL_SHAPE, RailShape.EAST_WEST));
        MinecartEntity cart = new MinecartEntity();
        cart.setPosition(0.2, 64.2625, 0.5);
        cart.motionX = 0.35;

        for (int i = 0; i < 5; i++) cart.tick(world);

        assertTrue(cart.x > 1.0, "Minecart blieb am Stützblock der Steigung hängen");
        assertTrue(cart.y >= 65.0625);
    }

    @Test
    void cartDescendsSlopeWithoutEnteringSupportBlocks() {
        TestWorld world = new TestWorld() {
            @Override public List<AABB> getCollisionBoxes(AABB area) {
                List<AABB> supports = List.of(
                        new AABB(-1, 63, 0, 0, 64, 1),
                        new AABB(0, 63, 0, 1, 64, 1),
                        new AABB(1, 64, 0, 2, 65, 1));
                return supports.stream().filter(area::intersects).toList();
            }
        };
        BlockState straight = BlockRegistry.get(Identifier.of("skyengine:rail")).getDefaultState()
                .with(Properties.RAIL_SHAPE, RailShape.EAST_WEST);
        world.put(-1, 64, 0, straight);
        world.put(0, 64, 0, BlockRegistry.get(Identifier.of("skyengine:rail")).getDefaultState()
                .with(Properties.RAIL_SHAPE, RailShape.ASCENDING_EAST));
        world.put(1, 65, 0, straight);
        MinecartEntity cart = new MinecartEntity();
        cart.setPosition(1.5, 65.0625, 0.5);
        cart.motionX = -0.35;

        for (int i = 0; i < 6; i++) cart.tick(world);

        assertTrue(cart.x < 0, "Minecart muss das untere Ende der Steigung erreichen");
        assertEquals(64.0625, cart.y, 1.0E-6, "x=" + cart.x + ", z=" + cart.z);
        assertEquals(0.5, cart.z, 1.0E-6);
    }

    @Test
    void handHitsAccumulateBeforeMinecartBreaks() {
        TestWorld world = new TestWorld();
        MinecartEntity cart = new MinecartEntity();
        cart.setPosition(0.5, 64, 0.5);

        for (int i = 0; i < 4; i++) cart.attack(world, false, false);
        assertFalse(cart.isRemoved());
        cart.attack(world, false, false);
        assertTrue(cart.isRemoved());
    }

    private static BlockState rail(String id, boolean powered) {
        return BlockRegistry.get(Identifier.of("skyengine:" + id)).getDefaultState()
                .with(Properties.STRAIGHT_RAIL_SHAPE, RailShape.EAST_WEST)
                .with(Properties.POWERED, powered);
    }

    private static class TestWorld extends World {
        private final LongIntMap blocks = new LongIntMap(32);

        TestWorld() { super("__minecart_test", level(), null, null); }
        void put(int x, int y, int z, BlockState state) {
            this.blocks.put(BlockPos.asLong(x, y, z), state.getId());
        }
        @Override public int getBlock(int x, int y, int z) {
            return this.blocks.getOrDefault(BlockPos.asLong(x, y, z), Blocks.AIR);
        }
        @Override public void markChunkModified(int x, int z) { }
        @Override public List<AABB> getCollisionBoxes(AABB area) { return List.of(); }

        private static LevelData level() {
            LevelData level = new LevelData();
            level.name = "minecart-test";
            level.seed = 1;
            level.worldType = "imported";
            return level;
        }
    }
}
