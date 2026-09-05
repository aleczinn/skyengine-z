package de.skyengine.game.world;

import de.skyengine.game.entity.ItemFrameEntity;
import de.skyengine.game.entity.EntityPlayer;
import de.skyengine.game.world.block.BlockPos;
import de.skyengine.game.world.block.Blocks;
import de.skyengine.game.world.block.Direction;
import de.skyengine.game.world.block.Identifier;
import de.skyengine.game.world.block.entity.BlockEntities;
import de.skyengine.game.world.block.entity.ComparatorBlockEntity;
import de.skyengine.game.world.block.state.BlockState;
import de.skyengine.game.world.block.state.BlockStateCodec;
import de.skyengine.game.world.block.state.Properties;
import de.skyengine.game.world.chunk.Chunk;
import de.skyengine.game.world.chunk.ChunkManager;
import de.skyengine.game.world.chunk.ChunkSection;
import de.skyengine.game.world.chunk.ChunkStatus;
import de.skyengine.game.world.item.ItemStack;
import de.skyengine.game.world.item.Items;
import de.skyengine.game.world.redstone.RedstoneWireNetwork;
import de.skyengine.game.world.save.ChunkSerializer;
import de.skyengine.game.world.save.LevelData;
import de.skyengine.test.BlocksTestBootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class WorldChunkRedstoneReconciliationTest {

    @BeforeAll
    static void bootstrapBlocks() {
        BlocksTestBootstrap.ensureBootstrapped();
    }

    @Test
    void readyChunkReconcilesWireAndStrongPowerAcrossItsLoadedSeam() throws Exception {
        TestWorld world = new TestWorld();
        Chunk west = readyChunk(0, 0);
        Chunk east = readyChunk(1, 0);
        world.install(west);
        world.install(east);
        west.loadSeeded = true;

        int wire = id("voxelstories:redstone_wire[east=none,north=none,power=0,south=none,west=none]");
        west.setBlock(ChunkSection.MASK, 64, 8, wire);
        east.setBlock(0, 64, 8, wire);
        west.setBlock(ChunkSection.MASK, 63, 8, id("voxelstories:stone"));
        east.setBlock(0, 63, 8, id("voxelstories:stone"));
        east.setBlock(1, 64, 8, id("voxelstories:redstone_block"));

        /* Der Hebel speist den Stein an der Kante stark; die Lampe liegt eine weitere Zelle
           im neuen Chunk. Damit beweist der Test das erforderliche Zwei-Zellen-Band. */
        west.setBlock(ChunkSection.MASK, 64, 12,
                id("voxelstories:lever[face=wall,facing=west,powered=true]"));
        east.setBlock(0, 64, 12, id("voxelstories:stone"));
        east.setBlock(1, 64, 12, id("voxelstories:redstone_lamp[lit=false]"));

        world.manager.requeueReadyAnnounce(east);
        world.processReadyChunks();

        BlockState westWire = Blocks.getState(west.getBlock(ChunkSection.MASK, 64, 8));
        BlockState eastWire = Blocks.getState(east.getBlock(0, 64, 8));
        assertTrue(westWire.get(Properties.WIRE_EAST).isConnected());
        assertTrue(eastWire.get(Properties.WIRE_WEST).isConnected());
        assertTrue(westWire.get(Properties.POWER) > 0);
        assertTrue(Blocks.getState(east.getBlock(1, 64, 12)).get(Properties.LIT));
        assertTrue(east.loadSeeded);

        /* Während eines Remeshs ist der verbleibende Chunk LIT und noch nicht editierbar.
           Der Unload muss die offene Kante daher bis zu dessen nächstem READY parken. */
        west.status = ChunkStatus.LIT;
        world.unload(east);
        world.processUnloadedChunkBoundaries();
        assertTrue(west.pendingRedstoneBoundaryMask != 0);
        assertTrue(Blocks.getState(west.getBlock(ChunkSection.MASK, 64, 8))
                .get(Properties.POWER) > 0);

        west.status = ChunkStatus.READY;
        world.manager.requeueReadyAnnounce(west);
        world.processReadyChunks();
        assertEquals(0, Blocks.getState(west.getBlock(ChunkSection.MASK, 64, 8))
                .get(Properties.POWER));
        assertEquals(0, west.pendingRedstoneBoundaryMask);
    }

    @Test
    void largePersistedWireMatrixRecoversAfterPartialUnloadAndRestart() throws Exception {
        TestWorld world = new TestWorld();
        Chunk west = readyChunk(0, 0);
        Chunk east = readyChunk(1, 0);
        world.install(west);
        world.install(east);
        west.loadSeeded = true;
        east.loadSeeded = true;

        int wire = id("voxelstories:redstone_wire[east=none,north=none,power=0,south=none,west=none]");
        int redstoneBlock = id("voxelstories:redstone_block");
        int width = ChunkSection.SIZE + 8;
        for (int z = 0; z < ChunkSection.SIZE; z++) {
            for (int x = 0; x < width; x++) {
                Chunk chunk = x < ChunkSection.SIZE ? west : east;
                chunk.setBlock(x & ChunkSection.MASK, 64, z, wire);
                chunk.setBlock(x & ChunkSection.MASK, 63, z, id("voxelstories:stone"));
            }
            /* Eine Quellenlinie an der Naht speist auch die acht Spalten im Ostchunk. */
            west.setBlock(ChunkSection.MASK, 63, z, redstoneBlock);
        }
        RedstoneWireNetwork.update(world, 0, 64, 0);
        assertTrue(Blocks.getState(world.getBlock(width - 1, 64, ChunkSection.MASK))
                .get(Properties.POWER) > 0);

        int[] stable = captureMatrix(world, width);
        byte[] westPayload = serialize(west);
        byte[] eastPayload = serialize(east);

        /* Partiell geladen: ohne den Quellen-Chunk muss die verbleibende Komponente ausgehen. */
        world.unload(west);
        world.processUnloadedChunkBoundaries();
        for (int z = 0; z < ChunkSection.SIZE; z++) {
            for (int x = ChunkSection.SIZE; x < width; x++) {
                assertEquals(0, Blocks.getState(world.getBlock(x, 64, z)).get(Properties.POWER));
            }
        }

        /* Derselbe Chunk kommt aus seinem Save zurück; READY-Publish muss die ganze Matrix heilen. */
        Chunk restoredWest = deserialize(0, 0, westPayload, world);
        world.install(restoredWest);
        world.manager.requeueReadyAnnounce(restoredWest);
        world.processReadyChunks();
        assertArrayEquals(stable, captureMatrix(world, width));

        /* Vollständiger Neustart: beide Seiten aus Payloads, Meldung absichtlich in Gegenrichtung. */
        TestWorld restarted = new TestWorld();
        Chunk restartedWest = deserialize(0, 0, westPayload, restarted);
        Chunk restartedEast = deserialize(1, 0, eastPayload, restarted);
        restarted.install(restartedWest);
        restarted.install(restartedEast);
        restarted.manager.requeueReadyAnnounce(restartedEast);
        restarted.manager.requeueReadyAnnounce(restartedWest);
        restarted.processReadyChunks();
        assertArrayEquals(stable, captureMatrix(restarted, width));
    }

    @Test
    void itemFrameAnalogSourceRecoversWhenItsChunkLoadsAfterComparatorChunk() throws Exception {
        Chunk west = readyChunk(0, 0);
        Chunk east = readyChunk(1, 0);
        int comparatorX = ChunkSection.MASK - 1;
        west.setBlock(comparatorX, 64, 8,
                id("voxelstories:comparator[facing=west,mode=compare,powered=false]"));
        west.setBlock(comparatorX, 63, 8, id("voxelstories:stone"));
        west.setBlock(ChunkSection.MASK, 64, 8, id("voxelstories:stone"));
        west.setBlockEntity(comparatorX, 64, 8, new ComparatorBlockEntity(
                BlockEntities.COMPARATOR, new BlockPos(comparatorX, 64, 8)));

        ItemFrameEntity frame = new ItemFrameEntity(ChunkSection.SIZE, 64, 8, Direction.EAST);
        frame.loadContent(new ItemStack(
                Items.get(Identifier.of("voxelstories:diamond")), 1), 5);
        east.addEntity(frame);

        byte[] westPayload = serialize(west);
        byte[] eastPayload = ChunkSerializer.serialize(east, "test", 1, false,
                List.of(), List.of(), ChunkSerializer.snapshotEntities(east));

        TestWorld restarted = new TestWorld();
        Chunk restoredWest = deserialize(0, 0, westPayload, restarted);
        restarted.install(restoredWest);
        restarted.manager.requeueReadyAnnounce(restoredWest);
        restarted.processReadyChunks();
        assertEquals(0, ((ComparatorBlockEntity) restoredWest.getBlockEntity(
                comparatorX, 64, 8)).getOutputSignal());

        Chunk restoredEast = deserialize(1, 0, eastPayload, restarted);
        restarted.install(restoredEast);
        restarted.manager.requeueReadyAnnounce(restoredEast);
        restarted.processReadyChunks();
        assertEquals(6, restarted.getItemFrameAnalogSignal(
                ChunkSection.SIZE, 64, 8, Direction.EAST));
        assertTrue(restarted.isTickScheduled(comparatorX, 64, 8),
                "der Zwei-Zellen-Nahtabgleich muss die geladene Frame-Quelle erkennen");

        restarted.advanceGameTime();
        restarted.tickScheduled();
        restarted.advanceGameTime();
        restarted.tickScheduled();

        ComparatorBlockEntity comparator = (ComparatorBlockEntity) restoredWest.getBlockEntity(
                comparatorX, 64, 8);
        assertEquals(6, comparator.getOutputSignal());
        assertTrue(Blocks.getState(restoredWest.getBlock(comparatorX, 64, 8))
                .get(Properties.POWERED));
    }

    private static byte[] serialize(Chunk chunk) {
        return ChunkSerializer.serialize(chunk, "test", 1, false, List.of(),
                ChunkSerializer.snapshotBlockEntities(chunk));
    }

    private static Chunk deserialize(int chunkX, int chunkZ, byte[] payload, Dimension world) throws Exception {
        Chunk chunk = new Chunk(chunkX, chunkZ);
        ChunkSerializer.deserialize(chunk, payload, world);
        chunk.status = ChunkStatus.READY;
        return chunk;
    }

    private static int[] captureMatrix(Dimension world, int width) {
        int[] states = new int[width * ChunkSection.SIZE];
        int i = 0;
        for (int z = 0; z < ChunkSection.SIZE; z++) {
            for (int x = 0; x < width; x++) states[i++] = world.getBlock(x, 64, z);
        }
        return states;
    }

    private static Chunk readyChunk(int x, int z) {
        Chunk chunk = new Chunk(x, z);
        chunk.status = ChunkStatus.READY;
        return chunk;
    }

    private static int id(String encoded) {
        BlockState state = BlockStateCodec.decode(encoded);
        if (state == null) throw new IllegalStateException("Test-State fehlt: " + encoded);
        return state.getId();
    }

    private static final class TestWorld extends Dimension {
        private static final Field CHUNKS_FIELD;
        private static final Field UNLOAD_QUEUE_FIELD;
        private static final Method PROCESS_READY;
        private static final Method PROCESS_UNLOADED;
        private static final Field GAME_TIME_FIELD;
        private static final Field ACTIVE_PLAYERS_FIELD;
        private static final Method TICK_SCHEDULED;

        static {
            try {
                CHUNKS_FIELD = ChunkManager.class.getDeclaredField("chunks");
                CHUNKS_FIELD.setAccessible(true);
                UNLOAD_QUEUE_FIELD = ChunkManager.class.getDeclaredField("unloadAnnounceQueue");
                UNLOAD_QUEUE_FIELD.setAccessible(true);
                PROCESS_READY = Dimension.class.getDeclaredMethod("processReadyChunks");
                PROCESS_READY.setAccessible(true);
                PROCESS_UNLOADED = Dimension.class.getDeclaredMethod("processUnloadedChunkBoundaries");
                PROCESS_UNLOADED.setAccessible(true);
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

        TestWorld() throws ReflectiveOperationException {
            super("__chunk_redstone_test", level(), null, null);
            ACTIVE_PLAYERS_FIELD.set(this, List.of(new EntityPlayer()));
            Field field = Dimension.class.getDeclaredField("chunkManager");
            field.setAccessible(true);
            this.manager = (ChunkManager) field.get(this);
        }

        @SuppressWarnings("unchecked")
        void install(Chunk chunk) throws IllegalAccessException {
            ((Map<Long, Chunk>) CHUNKS_FIELD.get(this.manager))
                    .put(Chunk.key(chunk.chunkX, chunk.chunkZ), chunk);
        }

        void processReadyChunks() throws ReflectiveOperationException {
            PROCESS_READY.invoke(this);
        }

        @SuppressWarnings("unchecked")
        void unload(Chunk chunk) throws IllegalAccessException {
            ((Map<Long, Chunk>) CHUNKS_FIELD.get(this.manager))
                    .remove(Chunk.key(chunk.chunkX, chunk.chunkZ));
            ((ConcurrentLinkedQueue<Long>) UNLOAD_QUEUE_FIELD.get(this.manager))
                    .add(Chunk.key(chunk.chunkX, chunk.chunkZ));
        }

        void processUnloadedChunkBoundaries() throws ReflectiveOperationException {
            PROCESS_UNLOADED.invoke(this);
        }

        void advanceGameTime() throws IllegalAccessException {
            GAME_TIME_FIELD.setLong(this, GAME_TIME_FIELD.getLong(this) + 1);
        }

        void tickScheduled() throws ReflectiveOperationException {
            TICK_SCHEDULED.invoke(this);
        }

        @Override
        public boolean setBlock(int x, int y, int z, int block, boolean updateNeighbors) {
            Chunk chunk = this.manager.getChunk(x >> ChunkSection.SHIFT, z >> ChunkSection.SHIFT);
            if (chunk == null || chunk.status != ChunkStatus.READY) return false;
            chunk.setBlock(x & ChunkSection.MASK, y, z & ChunkSection.MASK, block);
            return true;
        }

        private static LevelData level() {
            LevelData level = new LevelData();
            level.name = "chunk-redstone-test";
            level.seed = 1;

            return level;
        }
    }
}
