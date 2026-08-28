package de.skyengine.game.world.dimension;

import de.skyengine.game.world.Dimension;
import de.skyengine.game.world.block.Blocks;
import de.skyengine.game.world.block.Direction;
import de.skyengine.game.world.block.Identifier;
import de.skyengine.game.world.block.state.BlockState;
import de.skyengine.game.world.block.state.Properties;
import de.skyengine.game.world.chunk.Chunk;
import de.skyengine.game.world.chunk.ChunkManager;
import de.skyengine.game.world.chunk.ChunkStatus;
import de.skyengine.game.world.save.LevelData;
import de.skyengine.test.BlocksTestBootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

final class NetherPortalShapeTest {

    private static final Identifier TYPE = Identifier.of("voxelstories:nether_portal");

    @BeforeAll
    static void bootstrapBlocks() {
        BlocksTestBootstrap.ensureBootstrapped();
    }

    @Test
    void activatesFramesOnBothHorizontalAxes() throws Exception {
        for (Direction.Axis axis : new Direction.Axis[]{Direction.Axis.X, Direction.Axis.Z}) {
            TestWorld world = new TestWorld(axis.name());
            Chunk chunk = new Chunk(0, 0);
            chunk.status = ChunkStatus.READY;
            buildFrame(chunk, axis, 5, 64, 5, 2, 3);
            world.install(chunk);

            assertTrue(NetherPortalShape.activateNear(world, 5, 64, 5), axis.name());
            assertTrue(NetherPortalShape.isPortalState(world.getBlock(5, 64, 5)));
            assertEquals(axis, Blocks.getState(world.getBlock(5, 64, 5))
                    .get(Properties.HORIZONTAL_AXIS));
        }
    }

    @Test
    void collapseConsumesSurfaceOnlyOnce() throws Exception {
        TestWorld world = new TestWorld("collapse");
        Chunk chunk = new Chunk(0, 0);
        chunk.status = ChunkStatus.READY;
        buildFrame(chunk, Direction.Axis.Z, 5, 64, 5, 2, 3);
        world.install(chunk);
        assertTrue(NetherPortalShape.activate(world, 5, 64, 5));
        PortalIndex.Entry original = world.getPortalIndex().containing(TYPE, 5, 65, 5);
        assertNotNull(original);
        world.getPortalLinks().pair(TYPE, world.getDimensionId(), original.id(),
                WorldgenRegistries.NETHER, "target-portal");

        NetherPortalShape.Collapse collapse = NetherPortalShape.collapse(
                world, 5, 65, 5, Direction.Axis.Z);
        assertNotNull(collapse);
        assertEquals(6, collapse.blocks());
        assertEquals(1, countPortalBlocks(world));
        world.setBlock(5, 65, 5, Blocks.AIR, false);
        assertNull(NetherPortalShape.collapse(world, 5, 65, 5, Direction.Axis.Z));
        PortalIndex.Entry inactive = world.getPortalIndex().byId(original.id());
        assertNotNull(inactive);
        assertFalse(inactive.active());
        assertEquals("target-portal", world.getPortalLinks()
                .linked(TYPE, world.getDimensionId(), original.id()).portalId());

        assertTrue(NetherPortalShape.activate(world, 5, 64, 5));
        assertEquals(original.id(), world.getPortalIndex().containing(TYPE, 5, 64, 5).id());
    }

    @Test
    void brokenFrameRemovesPortalIdentityAndReleasesItsLink() throws Exception {
        TestWorld world = new TestWorld("broken_frame_link");
        Chunk chunk = new Chunk(0, 0);
        chunk.status = ChunkStatus.READY;
        buildFrame(chunk, Direction.Axis.Z, 5, 64, 5, 2, 3);
        world.install(chunk);
        assertTrue(NetherPortalShape.activate(world, 5, 64, 5));
        PortalIndex.Entry original = world.getPortalIndex().containing(TYPE, 5, 64, 5);
        world.getPortalLinks().pair(TYPE, world.getDimensionId(), original.id(),
                WorldgenRegistries.NETHER, "target-to-release");

        world.setBlock(5, 64, 4, Blocks.AIR, false);
        BlockState portal = Blocks.getState(world.getBlock(5, 64, 5));
        BlockState invalid = portal.getBlock().getStateForNeighborUpdate(
                world, 5, 64, 5, portal);
        assertTrue(invalid.isAir());
        world.setBlock(5, 64, 5, invalid.getId(), false);

        assertNull(world.getPortalIndex().byId(original.id()));
        assertNull(world.getPortalLinks().linked(TYPE, world.getDimensionId(), original.id()));
    }

    @Test
    void resizedInactiveFrameGetsNewIdentityAndReleasesOldLink() throws Exception {
        TestWorld world = new TestWorld("resized_frame_link");
        Chunk chunk = new Chunk(0, 0);
        chunk.status = ChunkStatus.READY;
        buildFrame(chunk, Direction.Axis.Z, 5, 64, 5, 2, 3);
        world.install(chunk);
        assertTrue(NetherPortalShape.activate(world, 5, 64, 5));
        PortalIndex.Entry original = world.getPortalIndex().containing(TYPE, 5, 64, 5);
        world.getPortalLinks().pair(TYPE, world.getDimensionId(), original.id(),
                WorldgenRegistries.NETHER, "old-target");

        NetherPortalShape.collapse(world, 5, 64, 5, Direction.Axis.Z);
        world.setBlock(5, 64, 5, Blocks.AIR, false);
        for (int y = 64; y < 67; y++) chunk.setBlock(5, y, 7, Blocks.AIR);
        buildFrame(chunk, Direction.Axis.Z, 5, 64, 5, 3, 3);

        assertTrue(NetherPortalShape.activate(world, 5, 64, 5));
        PortalIndex.Entry replacement = world.getPortalIndex().containing(TYPE, 5, 64, 5);
        assertNotNull(replacement);
        assertNotEquals(original.id(), replacement.id());
        assertEquals(3, replacement.width());
        assertNull(world.getPortalIndex().byId(original.id()));
        assertNull(world.getPortalLinks().linked(TYPE, world.getDimensionId(), original.id()));
    }

    private static int countPortalBlocks(Dimension world) {
        int count = 0;
        for (int y = 64; y < 67; y++) for (int z = 5; z < 7; z++) {
            if (NetherPortalShape.isPortalState(world.getBlock(5, y, z))) count++;
        }
        return count;
    }

    private static void buildFrame(Chunk chunk, Direction.Axis axis, int x, int y, int z,
                                   int width, int height) {
        int sx = axis == Direction.Axis.X ? 1 : 0;
        int sz = axis == Direction.Axis.Z ? 1 : 0;
        for (int w = -1; w <= width; w++) {
            chunk.setBlock(x + sx * w, y - 1, z + sz * w, Blocks.OBSIDIAN);
            chunk.setBlock(x + sx * w, y + height, z + sz * w, Blocks.OBSIDIAN);
        }
        for (int h = 0; h < height; h++) {
            chunk.setBlock(x - sx, y + h, z - sz, Blocks.OBSIDIAN);
            chunk.setBlock(x + sx * width, y + h, z + sz * width, Blocks.OBSIDIAN);
        }
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
        private final ChunkManager manager;

        TestWorld(String name) throws ReflectiveOperationException {
            super("__nether_portal_" + name, level(name), null, null);
            Field field = Dimension.class.getDeclaredField("chunkManager");
            field.setAccessible(true);
            this.manager = (ChunkManager) field.get(this);
        }

        @SuppressWarnings("unchecked")
        void install(Chunk chunk) throws IllegalAccessException {
            ((Map<Long, Chunk>) CHUNKS_FIELD.get(this.manager))
                    .put(Chunk.key(chunk.chunkX, chunk.chunkZ), chunk);
        }

        @Override public void updateNeighbors(int x, int y, int z) {}

        private static LevelData level(String name) {
            LevelData level = new LevelData();
            level.name = "nether-portal-" + name;
            level.seed = 1;

            return level;
        }
    }
}
