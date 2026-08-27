package de.skyengine.game.world.structure;

import de.skyengine.game.world.Dimension;
import de.skyengine.game.world.block.BlockPos;
import de.skyengine.game.world.block.Blocks;
import de.skyengine.game.world.block.Identifier;
import de.skyengine.game.world.block.entity.BlockEntities;
import de.skyengine.game.world.block.entity.BlockEntity;
import de.skyengine.game.world.block.entity.ChestBlockEntity;
import de.skyengine.game.world.block.entity.DataTag;
import de.skyengine.game.world.block.entity.PistonMovingBlockEntity;
import de.skyengine.game.world.chunk.Chunk;
import de.skyengine.game.world.chunk.ChunkManager;
import de.skyengine.game.world.chunk.ChunkStatus;
import de.skyengine.game.world.block.state.Properties;
import de.skyengine.game.world.item.ItemStack;
import de.skyengine.game.world.item.Items;
import de.skyengine.game.world.save.LevelData;
import de.skyengine.test.BlocksTestBootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

final class StructureBlockEntityTest {
    @BeforeAll static void bootstrap() { BlocksTestBootstrap.ensureBootstrapped(); }

    @Test
    void versionTwoRoundTripPreservesChestInventoryAndFingerprint(@TempDir Path temp) throws Exception {
        StructureTemplate.BlockEntitySnapshot snapshot = chestSnapshot(7);
        StructureTemplate original = template(snapshot);
        Path file = temp.resolve("chest.structure");

        StructureSerializer.write(file, original);
        StructureTemplate restored = StructureSerializer.read(file, original.id());

        assertEquals(original.cells(), restored.cells());
        assertEquals(original.fingerprint(), restored.fingerprint());
        ChestBlockEntity chest = new ChestBlockEntity(BlockEntities.CHEST, new BlockPos(0, 0, 0));
        chest.load(restored.cells().getFirst().blockEntity().data());
        assertEquals(7, chest.getInventory().get(3).getCount());
        assertNotEquals(original.fingerprint(), template(chestSnapshot(8)).fingerprint());
    }

    @Test
    void generatedChunkMaterializesStoredAndDefaultBlockEntities() {
        Chunk chunk = new Chunk(0, 0);
        chunk.setBlock(31, 70, 4, Blocks.CHEST);
        chunk.queueStructureBlockEntity(31, 70, 4, chestSnapshot(5));
        chunk.setBlock(30, 70, 4, Blocks.CHEST);
        chunk.queueStructureBlockEntity(30, 70, 4, null);

        chunk.materializeStructureBlockEntities();

        ChestBlockEntity stored = assertInstanceOf(ChestBlockEntity.class,
                chunk.getBlockEntity(31, 70, 4));
        ChestBlockEntity empty = assertInstanceOf(ChestBlockEntity.class,
                chunk.getBlockEntity(30, 70, 4));
        assertEquals(5, stored.getInventory().get(3).getCount());
        assertTrue(empty.getInventory().get(3).isEmpty());
        assertNull(stored.getWorld(), "Dimension wird erst beim READY-Publish angehaengt");
    }

    @Test
    void sameStatePasteReplacesInventoryAndUndoRedoRestoreBothVersions() throws Exception {
        TestWorld world = new TestWorld();
        Chunk chunk = new Chunk(0, 0);
        chunk.status = ChunkStatus.READY;
        chunk.setBlock(1, 64, 1, Blocks.CHEST);
        ChestBlockEntity existing = new ChestBlockEntity(BlockEntities.CHEST, new BlockPos(1, 64, 1));
        existing.setWorld(world);
        existing.getInventory().set(3, stack(2));
        chunk.setBlockEntity(1, 64, 1, existing);
        world.install(chunk);

        StructurePlacement placement = new StructurePlacement();
        StructurePlacement.Plan plan = placement.prepareInWorld(template(chestSnapshot(9)), world,
                1, 64, 1, StructureTransform.IDENTITY, StructurePlacement.Rule.REPLACE_ALL);
        assertEquals(1, plan.count(), "BE-Daten duerfen bei identischem BlockState nicht uebersprungen werden");
        WorldEditHistory history = new WorldEditHistory();

        history.apply(world, placement, plan);
        assertEquals(9, chest(world).getInventory().get(3).getCount());
        history.undo(world, placement, 1);
        assertEquals(2, chest(world).getInventory().get(3).getCount());
        history.redo(world, placement, 1);
        assertEquals(9, chest(world).getInventory().get(3).getCount());
    }

    @Test
    void connectedStatesNormalizeWithoutTurningThePasteIntoAFailure() throws Exception {
        TestWorld world = new TestWorld();
        Chunk chunk = new Chunk(0, 0);
        chunk.status = ChunkStatus.READY;
        world.install(chunk);
        world.enableNeighborUpdates();
        StructureTemplate fences = new StructureTemplate(Identifier.of("test:fences"),
                2, 1, 1, 0, 0, 0, List.of(
                new StructureTemplate.Cell(0, 0, 0, Blocks.SPRUCE_FENCE),
                new StructureTemplate.Cell(1, 0, 0, Blocks.SPRUCE_FENCE)));
        StructurePlacement placement = new StructurePlacement();
        StructurePlacement.Plan plan = placement.prepareInWorld(fences, world,
                1, 64, 1, StructureTransform.IDENTITY, StructurePlacement.Rule.REPLACE_ALL);

        StructurePlacement.Result result = placement.applyPlan(world, plan, true);

        assertTrue(result.complete());
        assertEquals(2, result.written());
        assertTrue(Blocks.getState(world.getBlock(1, 64, 1)).get(Properties.EAST));
        assertTrue(Blocks.getState(world.getBlock(2, 64, 1)).get(Properties.WEST));
    }

    @Test
    void explicitAirCanBeReplacedIgnoredOrCombinedWithKeepExisting() throws Exception {
        TestWorld world = new TestWorld();
        Chunk chunk = new Chunk(0, 0);
        chunk.status = ChunkStatus.READY;
        chunk.setBlock(1, 64, 1, Blocks.DIRT);
        chunk.setBlock(2, 64, 1, Blocks.DIRT);
        world.install(chunk);
        StructureTemplate template = new StructureTemplate(Identifier.of("test:explicit_air"),
                2, 1, 1, 0, 0, 0, List.of(
                new StructureTemplate.Cell(0, 0, 0, Blocks.STONE),
                new StructureTemplate.Cell(1, 0, 0, Blocks.AIR)));
        StructurePlacement placement = new StructurePlacement();

        StructurePlacement.Result ignored = placement.placeInWorld(template, world,
                1, 64, 1, StructureTransform.IDENTITY, StructurePlacement.Rule.IGNORE_AIR);
        assertTrue(ignored.complete());
        assertEquals(1, ignored.written());
        assertEquals(1, ignored.skipped());
        assertEquals(Blocks.STONE, world.getBlock(1, 64, 1));
        assertEquals(Blocks.DIRT, world.getBlock(2, 64, 1));

        world.setBlock(1, 64, 1, Blocks.DIRT, false);
        StructurePlacement.Result kept = placement.placeInWorld(template, world,
                1, 64, 1, StructureTransform.IDENTITY, StructurePlacement.Rule.KEEP_EXISTING);
        assertTrue(kept.complete());
        assertEquals(0, kept.written());
        assertEquals(2, kept.skipped());
        assertEquals(Blocks.DIRT, world.getBlock(1, 64, 1));
        assertEquals(Blocks.DIRT, world.getBlock(2, 64, 1));

        StructurePlacement.Result replaced = placement.placeInWorld(template, world,
                1, 64, 1, StructureTransform.IDENTITY, StructurePlacement.Rule.REPLACE_ALL);
        assertTrue(replaced.complete());
        assertEquals(2, replaced.written());
        assertEquals(Blocks.STONE, world.getBlock(1, 64, 1));
        assertEquals(Blocks.AIR, world.getBlock(2, 64, 1));
    }

    @Test
    void bundledBigSprucePastesAllCellsDespiteFenceNormalization() throws Exception {
        TestWorld world = new TestWorld();
        Chunk chunk = new Chunk(0, 0);
        chunk.status = ChunkStatus.READY;
        world.install(chunk);
        world.enableNeighborUpdates();
        StructureTemplate spruce = StructureTemplateManager.loadResource(
                Identifier.of("skyengine:trees/spruce/big_spruce_3"));
        assertNotNull(spruce);
        StructurePlacement placement = new StructurePlacement();
        StructurePlacement.Plan plan = placement.prepareInWorld(spruce, world,
                1, 64, 1, StructureTransform.IDENTITY, StructurePlacement.Rule.REPLACE_ALL);

        StructurePlacement.Result result = placement.applyPlan(world, plan, true);

        assertTrue(result.complete());
        assertEquals(spruce.cells().size(), result.written());
        assertEquals(0, result.failed());
    }

    @Test
    void pasteCanSelectTheTransformedBoundsIncludingANoOp(@TempDir Path temp) throws Exception {
        TestWorld world = new TestWorld();
        Chunk chunk = new Chunk(0, 0);
        chunk.status = ChunkStatus.READY;
        world.install(chunk);
        StructureTemplateManager manager = new StructureTemplateManager(
                temp.resolve("structures"), temp.resolve("saves").toFile());
        StructureTemplate template = new StructureTemplate(Identifier.of("test:selection"),
                3, 2, 2, 0, 0, 0,
                List.of(new StructureTemplate.Cell(0, 0, 0, Blocks.STONE)));
        manager.saveAuthored(template, false);
        WorldEditSession editor = new WorldEditService(manager).session(UUID.randomUUID());
        editor.load("test:selection");
        editor.rotate(90);
        StructureBounds expected = WorldEditSession.bounds(editor.clipboard(), 10, 64, 8);

        StructurePlacement.Result first = editor.paste(world, 10, 64, 8,
                StructurePlacement.Rule.REPLACE_ALL, true);
        assertTrue(first.complete());
        assertEquals(expected, editor.selection().bounds());
        assertNull(editor.structureAnchor());

        editor.clearSelection();
        StructurePlacement.Result noOp = editor.paste(world, 10, 64, 8,
                StructurePlacement.Rule.REPLACE_ALL, true);
        assertTrue(noOp.complete());
        assertEquals(0, noOp.written());
        assertEquals(expected, editor.selection().bounds());
    }

    @Test
    void captureStoresPersistentEntitiesAndRejectsMovingPistons() throws Exception {
        TestWorld world = new TestWorld();
        Chunk chunk = new Chunk(0, 0);
        chunk.status = ChunkStatus.READY;
        chunk.setBlock(2, 64, 2, Blocks.CHEST);
        ChestBlockEntity chest = new ChestBlockEntity(BlockEntities.CHEST, new BlockPos(2, 64, 2));
        chest.setWorld(world);
        chest.getInventory().set(3, stack(4));
        chunk.setBlockEntity(2, 64, 2, chest);
        world.install(chunk);
        WorldEditSelection selection = new WorldEditSelection(world.getDimensionId(),
                new BlockPos(2, 64, 2), new BlockPos(2, 64, 2));

        StructureTemplate captured = new StructureTemplateBuilder().capture(world, selection,
                Identifier.of("test:captured_chest"), false, new BlockPos(2, 64, 2));
        assertEquals(4, captured.cells().getFirst().blockEntity().data()
                .getTag("inventory").getTag("slot3").getInt("count", 0));

        chunk.setBlock(2, 64, 2, Blocks.MOVING_PISTON);
        PistonMovingBlockEntity moving = new PistonMovingBlockEntity(BlockEntities.PISTON_MOVING,
                new BlockPos(2, 64, 2));
        moving.setWorld(world);
        chunk.setBlockEntity(2, 64, 2, moving);
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                new StructureTemplateBuilder().capture(world, selection,
                        Identifier.of("test:moving"), false, new BlockPos(2, 64, 2)));
        assertTrue(error.getMessage().contains("piston_moving"));
    }

    @Test
    void editorStackOverlappingMoveAndCutShareBlockEntityAwareHistory(@TempDir Path temp) throws Exception {
        TestWorld world = new TestWorld();
        Chunk chunk = new Chunk(0, 0);
        chunk.status = ChunkStatus.READY;
        chunk.setBlock(1, 64, 1, Blocks.CHEST);
        ChestBlockEntity chest = new ChestBlockEntity(BlockEntities.CHEST, new BlockPos(1, 64, 1));
        chest.setWorld(world);
        chest.getInventory().set(3, stack(4));
        chunk.setBlockEntity(1, 64, 1, chest);
        chunk.setBlock(2, 64, 1, Blocks.STONE);
        world.install(chunk);

        StructureTemplateManager manager = new StructureTemplateManager(
                temp.resolve("structures"), temp.toFile());
        WorldEditSession editor = new WorldEditService(manager).session(UUID.randomUUID());
        editor.pos1(world.getDimensionId(), 1, 64, 1);
        editor.pos2(world.getDimensionId(), 2, 64, 1);

        editor.stack(world, de.skyengine.game.world.block.Direction.EAST, 1);
        assertEquals(4, assertInstanceOf(ChestBlockEntity.class,
                world.getBlockEntity(3, 64, 1)).getInventory().get(3).getCount());
        assertEquals(Blocks.STONE, world.getBlock(4, 64, 1));
        editor.undo(world, 1);
        assertEquals(Blocks.AIR, world.getBlock(3, 64, 1));
        assertEquals(Blocks.AIR, world.getBlock(4, 64, 1));

        editor.move(world, de.skyengine.game.world.block.Direction.EAST, 1);
        assertEquals(Blocks.AIR, world.getBlock(1, 64, 1));
        assertEquals(4, assertInstanceOf(ChestBlockEntity.class,
                world.getBlockEntity(2, 64, 1)).getInventory().get(3).getCount());
        assertEquals(Blocks.STONE, world.getBlock(3, 64, 1));
        assertEquals(new BlockPos(1, 64, 1), editor.selection().pos1());
        editor.undo(world, 1);
        assertEquals(4, assertInstanceOf(ChestBlockEntity.class,
                world.getBlockEntity(1, 64, 1)).getInventory().get(3).getCount());
        assertEquals(Blocks.STONE, world.getBlock(2, 64, 1));

        editor.cut(world, 1, 64, 1, WorldEditSession.OperationOrigin.PLAYER);
        assertEquals(Blocks.AIR, world.getBlock(1, 64, 1));
        assertNotNull(editor.clipboard().template().cells().getFirst().blockEntity());
        editor.undo(world, 1);
        assertEquals(4, assertInstanceOf(ChestBlockEntity.class,
                world.getBlockEntity(1, 64, 1)).getInventory().get(3).getCount());
    }

    @Test
    void structureSaveChoosesPlayerOrExplicitToolAnchor(@TempDir Path temp) throws Exception {
        TestWorld world = new TestWorld();
        Chunk chunk = new Chunk(0, 0);
        chunk.status = ChunkStatus.READY;
        chunk.setBlock(1, 64, 1, Blocks.STONE);
        chunk.setBlock(2, 64, 1, Blocks.DIRT);
        chunk.setBlock(3, 64, 1, Blocks.STONE);
        world.install(chunk);

        WorldEditSession editor = new WorldEditService(new StructureTemplateManager(
                temp.resolve("structures"), temp.toFile())).session(UUID.randomUUID());
        editor.pos1(world.getDimensionId(), 1, 64, 1);
        editor.pos2(world.getDimensionId(), 3, 64, 1);
        editor.anchor(world.getDimensionId(), 3, 64, 1);

        StructureTemplate playerOrigin = editor.save(world, "test:player-origin", false, false,
                2, 64, 1, WorldEditSession.OperationOrigin.PLAYER);
        assertEquals(1, playerOrigin.anchorX());
        assertEquals(0, playerOrigin.anchorY());
        assertEquals(0, playerOrigin.anchorZ());

        StructureTemplate toolOrigin = editor.save(world, "test:tool-origin", false, false,
                2, 64, 1, WorldEditSession.OperationOrigin.ANCHOR);
        assertEquals(2, toolOrigin.anchorX());
        assertEquals(0, toolOrigin.anchorY());
        assertEquals(0, toolOrigin.anchorZ());

        assertThrows(IllegalArgumentException.class, () -> editor.save(world,
                "test:outside", false, false, 8, 64, 1,
                WorldEditSession.OperationOrigin.PLAYER));
    }

    @Test
    void editorRegenUsesFreshGeneratorSnapshotAndCanBeUndone(@TempDir Path temp) throws Exception {
        TestWorld world = new TestWorld(false);
        Chunk generated = world.generateWorldgenSnapshot(0, 0);
        int expected = generated.getBlock(1, 1, 1);
        int modified = expected == Blocks.AIR ? Blocks.STONE : Blocks.AIR;
        Chunk live = new Chunk(0, 0);
        live.status = ChunkStatus.READY;
        live.setBlock(1, 1, 1, modified);
        world.install(live);

        WorldEditSession editor = new WorldEditService(new StructureTemplateManager(
                temp.resolve("structures"), temp.toFile())).session(UUID.randomUUID());
        editor.pos1(world.getDimensionId(), 1, 1, 1);
        editor.pos2(world.getDimensionId(), 1, 1, 1);

        editor.regenerate(world);
        assertEquals(expected, world.getBlock(1, 1, 1));
        editor.undo(world, 1);
        assertEquals(modified, world.getBlock(1, 1, 1));
    }

    private static StructureTemplate template(StructureTemplate.BlockEntitySnapshot snapshot) {
        return new StructureTemplate(Identifier.of("test:chest"), 1, 1, 1, 0, 0, 0,
                List.of(new StructureTemplate.Cell(0, 0, 0, Blocks.CHEST, snapshot)));
    }

    private static StructureTemplate.BlockEntitySnapshot chestSnapshot(int count) {
        ChestBlockEntity chest = new ChestBlockEntity(BlockEntities.CHEST, new BlockPos(0, 0, 0));
        chest.getInventory().set(3, stack(count));
        DataTag data = new DataTag();
        chest.save(data);
        return new StructureTemplate.BlockEntitySnapshot(Identifier.of("skyengine:chest"), data);
    }

    private static ItemStack stack(int count) {
        return new ItemStack(Items.get(Identifier.of("skyengine:stone")), count);
    }

    private static ChestBlockEntity chest(TestWorld world) {
        return assertInstanceOf(ChestBlockEntity.class, world.getBlockEntity(1, 64, 1));
    }

    private static final class TestWorld extends Dimension {
        private static final Field CHUNKS_FIELD;
        private final ChunkManager manager;
        private boolean neighborUpdates;

        static {
            try {
                CHUNKS_FIELD = ChunkManager.class.getDeclaredField("chunks");
                CHUNKS_FIELD.setAccessible(true);
            } catch (ReflectiveOperationException e) {
                throw new ExceptionInInitializerError(e);
            }
        }

        TestWorld() throws ReflectiveOperationException { this(true); }

        TestWorld(boolean imported) throws ReflectiveOperationException {
            super(imported ? "__structure_be_test" : "__structure_regen_test",
                    level(imported), null, null);
            Field field = Dimension.class.getDeclaredField("chunkManager");
            field.setAccessible(true);
            this.manager = (ChunkManager) field.get(this);
        }

        @SuppressWarnings("unchecked")
        void install(Chunk chunk) throws IllegalAccessException {
            ((Map<Long, Chunk>) CHUNKS_FIELD.get(this.manager)).put(Chunk.key(0, 0), chunk);
        }

        void enableNeighborUpdates() { this.neighborUpdates = true; }

        @Override public void updateNeighbors(int x, int y, int z) {
            if (this.neighborUpdates) super.updateNeighbors(x, y, z);
        }

        private static LevelData level(boolean imported) {
            LevelData level = new LevelData();
            level.name = "structure-be-test";
            level.seed = 1;
            level.worldType = imported ? "imported" : "default";
            return level;
        }
    }
}
