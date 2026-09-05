package de.skyengine.game.world.block.behavior;

import de.skyengine.game.world.Dimension;
import de.skyengine.game.entity.EntityPlayer;
import de.skyengine.game.world.block.Blocks;
import de.skyengine.game.world.block.state.BlockState;
import de.skyengine.game.world.block.state.Properties;
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
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class FluidBehaviorSimulationTest {

    private static final FluidBehavior BEHAVIOR = new FluidBehavior();

    @BeforeAll
    static void bootstrapBlocks() {
        BlocksTestBootstrap.ensureBootstrapped();
    }

    @Test
    void waterAndLavaKeepTheirOverworldHorizontalReach() throws Exception {
        TestWorld waterWorld = flatWorld();
        waterWorld.put(16, 64, 16, Blocks.WATER);
        waterWorld.scheduleTick(16, 64, 16, 1);
        waterWorld.advanceTicks(45);

        assertFluid(waterWorld, 23, 64, 16, Blocks.WATER, 7, false);
        assertEquals(Blocks.AIR, waterWorld.getBlock(24, 64, 16));

        TestWorld lavaWorld = flatWorld();
        lavaWorld.put(16, 64, 16, Blocks.LAVA);
        lavaWorld.scheduleTick(16, 64, 16, 1);
        lavaWorld.advanceTicks(100);

        assertFluid(lavaWorld, 19, 64, 16, Blocks.LAVA, 6, false);
        assertEquals(Blocks.AIR, lavaWorld.getBlock(20, 64, 16));
    }

    @Test
    void waterWinningAnAirGapTurnsTheAdjacentLavaSourceIntoObsidian() throws Exception {
        TestWorld world = flatWorld();
        world.put(14, 64, 16, Blocks.WATER);
        world.put(16, 64, 16, Blocks.LAVA);

        BEHAVIOR.scheduledTick(world, 14, 64, 16, Blocks.getState(Blocks.WATER));

        assertFluid(world, 15, 64, 16, Blocks.WATER, 1, false);
        assertEquals(Blocks.OBSIDIAN, world.getBlock(16, 64, 16));
        assertEquals(1, world.fluidExtinguishSounds);
    }

    @Test
    void lavaWinningAnAirGapCreatesCobblestoneAtTheContactCell() throws Exception {
        TestWorld world = flatWorld();
        world.put(14, 64, 16, Blocks.WATER);
        world.put(16, 64, 16, Blocks.LAVA);

        BEHAVIOR.scheduledTick(world, 16, 64, 16, Blocks.getState(Blocks.LAVA));

        assertEquals(Blocks.COBBLESTONE, world.getBlock(15, 64, 16));
        assertEquals(Blocks.LAVA, world.getBlock(16, 64, 16));
        assertEquals(1, world.fluidExtinguishSounds);
    }

    @Test
    void sourceAndFlowingLavaReactDifferentlyToSideWater() throws Exception {
        TestWorld world = flatWorld();
        world.put(12, 64, 16, Blocks.LAVA);
        world.put(13, 64, 16, Blocks.WATER);
        world.put(20, 64, 16, fluid(Blocks.LAVA, 2, false));
        world.put(21, 64, 16, Blocks.WATER);

        world.updateBlockStateAt(12, 64, 16);
        world.updateBlockStateAt(20, 64, 16);

        assertEquals(Blocks.OBSIDIAN, world.getBlock(12, 64, 16));
        assertEquals(Blocks.COBBLESTONE, world.getBlock(20, 64, 16));
        assertEquals(2, world.fluidExtinguishSounds);
    }

    @Test
    void lavaFlowingDownIntoWaterReplacesTheWaterWithStone() throws Exception {
        TestWorld world = flatWorld();
        world.put(16, 65, 16, Blocks.LAVA);
        world.put(16, 64, 16, Blocks.WATER);

        BEHAVIOR.scheduledTick(world, 16, 65, 16, Blocks.getState(Blocks.LAVA));

        assertEquals(Blocks.STONE, world.getBlock(16, 64, 16));
        assertEquals(Blocks.LAVA, world.getBlock(16, 65, 16));
        assertEquals(1, world.fluidExtinguishSounds);
    }

    @Test
    void animateTicksProduceWaterAndLavaSurfaceSounds() throws Exception {
        TestWorld world = flatWorld();
        Random alwaysTrigger = new Random() {
            @Override
            public int nextInt(int bound) {
                return 0;
            }
        };

        BEHAVIOR.animateTick(world, 12, 64, 16,
                Blocks.getState(fluid(Blocks.WATER, 1, false)), alwaysTrigger);
        BEHAVIOR.animateTick(world, 20, 64, 16,
                Blocks.getState(Blocks.LAVA), alwaysTrigger);
        BEHAVIOR.animateTick(world, 12, 64, 16,
                Blocks.getState(Blocks.WATER), alwaysTrigger); // stille Quelle
        BEHAVIOR.animateTick(world, 12, 64, 16,
                Blocks.getState(fluid(Blocks.WATER, 0, true)), alwaysTrigger); // fallende Säule
        world.put(21, 65, 16, Blocks.STONE);
        BEHAVIOR.animateTick(world, 21, 64, 16,
                Blocks.getState(Blocks.LAVA), alwaysTrigger); // abgedeckte Lava

        assertEquals(1, world.waterAmbientSounds);
        assertEquals(1, world.lavaAmbientSounds);
        assertEquals(1, world.lavaPopSounds);
    }

    @Test
    void slopeSearchDoesNotLookThroughAFluidSource() throws Exception {
        TestWorld world = flatWorld();
        for (int x = 11; x <= 20; x++) {
            world.put(x, 64, 15, Blocks.STONE);
            world.put(x, 64, 17, Blocks.STONE);
        }
        world.put(16, 64, 16, Blocks.WATER);
        world.put(18, 64, 16, Blocks.WATER);
        world.put(12, 63, 16, Blocks.AIR); // erreichbarer Abfluss drei Schritte westlich
        world.put(19, 63, 16, Blocks.AIR); // nur hinter der blockierenden Ost-Quelle

        BEHAVIOR.scheduledTick(world, 16, 64, 16, Blocks.getState(Blocks.WATER));

        assertFluid(world, 15, 64, 16, Blocks.WATER, 1, false);
        assertEquals(Blocks.AIR, world.getBlock(17, 64, 16));
    }

    @Test
    void slopeSearchTreatsAnUnreadyNeighborChunkAsBlocked() throws Exception {
        TestWorld world = flatWorld();
        for (int x = 25; x <= 31; x++) {
            world.put(x, 64, 15, Blocks.STONE);
            world.put(x, 64, 17, Blocks.STONE);
        }
        world.put(30, 64, 16, Blocks.WATER);
        world.put(27, 63, 16, Blocks.AIR); // weiter entfernter, aber geladener Abfluss

        BEHAVIOR.scheduledTick(world, 30, 64, 16, Blocks.getState(Blocks.WATER));

        assertFluid(world, 29, 64, 16, Blocks.WATER, 1, false);
        assertEquals(Blocks.AIR, world.getBlock(31, 64, 16));
        assertTrue(world.snapshotScheduledTicks(world.chunk()).stream()
                .anyMatch(tick -> tick.x() == 30 && tick.y() == 64 && tick.z() == 16));
    }

    @Test
    void twoWaterSourcesCreateANewSourceOnlyWithSupport() throws Exception {
        TestWorld supported = flatWorld();
        supported.put(15, 64, 16, Blocks.WATER);
        supported.put(17, 64, 16, Blocks.WATER);
        supported.put(16, 64, 16, fluid(Blocks.WATER, 1, false));

        BEHAVIOR.scheduledTick(supported, 16, 64, 16,
                Blocks.getState(supported.getBlock(16, 64, 16)));
        assertEquals(Blocks.WATER, supported.getBlock(16, 64, 16));

        TestWorld unsupported = flatWorld();
        unsupported.put(15, 64, 16, Blocks.WATER);
        unsupported.put(17, 64, 16, Blocks.WATER);
        unsupported.put(16, 64, 16, fluid(Blocks.WATER, 1, false));
        unsupported.put(16, 63, 16, Blocks.AIR);

        BEHAVIOR.scheduledTick(unsupported, 16, 64, 16,
                Blocks.getState(unsupported.getBlock(16, 64, 16)));
        assertFalse(isSource(Blocks.getState(unsupported.getBlock(16, 64, 16))));
    }

    @Test
    void fallingStatesAreNormalizedToLevelZero() throws Exception {
        TestWorld world = flatWorld();
        world.put(16, 65, 16, Blocks.WATER);
        world.put(16, 64, 16, fluid(Blocks.WATER, 4, false));

        BEHAVIOR.scheduledTick(world, 16, 64, 16,
                Blocks.getState(world.getBlock(16, 64, 16)));

        assertFluid(world, 16, 64, 16, Blocks.WATER, 0, true);
    }

    @Test
    void weakeningHorizontalLavaCanUseTheVanillaFourfoldDelay() throws Exception {
        TestWorld world = flatWorld();
        world.random().setSeed(0L); // erster nextInt(4) ist ungleich 0
        world.put(16, 64, 16, fluid(Blocks.LAVA, 2, false));
        world.put(15, 64, 16, fluid(Blocks.LAVA, 4, false));

        BEHAVIOR.scheduledTick(world, 16, 64, 16,
                Blocks.getState(world.getBlock(16, 64, 16)));

        assertFluid(world, 16, 64, 16, Blocks.LAVA, 6, false);
        List<SavedTick> ticks = world.snapshotScheduledTicks(world.chunk());
        SavedTick own = ticks.stream()
                .filter(tick -> tick.x() == 16 && tick.y() == 64 && tick.z() == 16)
                .findFirst().orElseThrow();
        assertEquals(120, own.remainingTicks());
    }

    private static TestWorld flatWorld() throws Exception {
        TestWorld world = new TestWorld();
        for (int z = 0; z < 32; z++) {
            for (int x = 0; x < 32; x++) world.put(x, 63, z, Blocks.STONE);
        }
        return world;
    }

    private static void assertFluid(Dimension world, int x, int y, int z,
                                    int fluidId, int level, boolean falling) {
        BlockState actual = Blocks.getState(world.getBlock(x, y, z));
        assertTrue(actual.isFluid(), "expected fluid at " + x + "," + y + "," + z);
        assertEquals(Blocks.getState(fluidId).getBlock(), actual.getBlock());
        assertEquals(level, actual.get(Properties.LEVEL));
        assertEquals(falling, actual.get(Properties.FALLING));
    }

    private static int fluid(int defaultId, int level, boolean falling) {
        return Blocks.getState(defaultId).getBlock().getDefaultState()
                .with(Properties.LEVEL, level)
                .with(Properties.FALLING, falling)
                .getId();
    }

    private static boolean isSource(BlockState state) {
        return state.isFluid() && !state.get(Properties.FALLING)
                && state.get(Properties.LEVEL) == 0;
    }

    private static final class TestWorld extends Dimension {
        private static final Field CHUNKS_FIELD;
        private static final Field GAME_TIME_FIELD;
        private static final Field ACTIVE_PLAYERS_FIELD;
        private static final Method TICK_SCHEDULED;

        static {
            try {
                CHUNKS_FIELD = ChunkManager.class.getDeclaredField("chunks");
                CHUNKS_FIELD.setAccessible(true);
                GAME_TIME_FIELD = Dimension.class.getDeclaredField("gameTime");
                GAME_TIME_FIELD.setAccessible(true);
                ACTIVE_PLAYERS_FIELD = Dimension.class.getDeclaredField("activePlayers");
                ACTIVE_PLAYERS_FIELD.setAccessible(true);
                TICK_SCHEDULED = Dimension.class.getDeclaredMethod("tickScheduled");
                TICK_SCHEDULED.setAccessible(true);
            } catch (ReflectiveOperationException e) {
                throw new ExceptionInInitializerError(e);
            }
        }

        private final ChunkManager manager;
        private final Chunk chunk = new Chunk(0, 0);
        private int fluidExtinguishSounds;
        private int waterAmbientSounds;
        private int lavaAmbientSounds;
        private int lavaPopSounds;

        TestWorld() throws ReflectiveOperationException {
            super("__fluid_simulation_test", levelData(), null, null);
            ACTIVE_PLAYERS_FIELD.set(this, List.of(new EntityPlayer()));
            Field field = Dimension.class.getDeclaredField("chunkManager");
            field.setAccessible(true);
            this.manager = (ChunkManager) field.get(this);
            this.chunk.status = ChunkStatus.READY;
            this.install(this.chunk);
        }

        @SuppressWarnings("unchecked")
        private void install(Chunk installed) throws IllegalAccessException {
            ((Map<Long, Chunk>) CHUNKS_FIELD.get(this.manager))
                    .put(Chunk.key(installed.chunkX, installed.chunkZ), installed);
        }

        void put(int x, int y, int z, int block) {
            this.chunk.setBlock(x, y, z, block);
        }

        Chunk chunk() {
            return this.chunk;
        }

        @Override
        public void playFluidExtinguish(int x, int y, int z) {
            this.fluidExtinguishSounds++;
        }

        @Override
        public void playWaterAmbient(int x, int y, int z) {
            this.waterAmbientSounds++;
        }

        @Override
        public void playLavaAmbient(int x, int y, int z) {
            this.lavaAmbientSounds++;
        }

        @Override
        public void playLavaPop(int x, int y, int z) {
            this.lavaPopSounds++;
        }

        void advanceTicks(int ticks) throws ReflectiveOperationException {
            for (int i = 0; i < ticks; i++) {
                GAME_TIME_FIELD.setLong(this, this.getGameTime() + 1);
                TICK_SCHEDULED.invoke(this);
            }
        }

        private static LevelData levelData() {
            LevelData level = new LevelData();
            level.name = "fluid-simulation-test";
            level.seed = 1;

            return level;
        }
    }
}
