package de.skyengine.game.world.structure;

import de.skyengine.game.world.block.Blocks;
import de.skyengine.game.world.block.Identifier;
import de.skyengine.mcimport.mapping.BlockMapper;
import de.skyengine.mcimport.nbt.NbtCompound;
import de.skyengine.mcimport.nbt.NbtList;
import de.skyengine.mcimport.nbt.NbtTag;
import de.skyengine.mcimport.nbt.NbtWriter;
import de.skyengine.test.BlocksTestBootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.*;

final class StructureFormatTest {
    @BeforeAll static void bootstrap() { BlocksTestBootstrap.ensureBootstrapped(); }

    @Test
    void nativeFormatRoundTripsSparseCellsAndExplicitAir(@TempDir Path temp) throws Exception {
        Identifier id = Identifier.of("test:sparse/tree");
        StructureTemplate original = new StructureTemplate(id, 3, 4, 5, 1, 0, 2, List.of(
                new StructureTemplate.Cell(1, 0, 2, Blocks.SPRUCE_LOG),
                new StructureTemplate.Cell(1, 1, 2, Blocks.AIR),
                new StructureTemplate.Cell(2, 2, 2, Blocks.SPRUCE_LEAVES)));
        Path file = temp.resolve("tree.structure");
        StructureSerializer.write(file, original);
        Path second = temp.resolve("tree-copy.structure");
        StructureSerializer.write(second, original);
        StructureTemplate restored = StructureSerializer.read(file, id);
        assertEquals(original.cells(), restored.cells());
        assertEquals(original.fingerprint(), restored.fingerprint());
        assertTrue(restored.hasExplicitAir());
        assertEquals(StructureSerializer.MAGIC, java.nio.ByteBuffer.wrap(Files.readAllBytes(file), 0, 4).getInt());
        assertArrayEquals(Files.readAllBytes(file), Files.readAllBytes(second));
    }

    @Test
    void versionOneNativeFilesRemainReadable(@TempDir Path temp) throws Exception {
        Path file = temp.resolve("v1.structure");
        NbtList palette = new NbtList((byte) 8);
        palette.add(new NbtTag.NbtString("skyengine:stone"));
        NbtCompound root = new NbtCompound()
                .put("Id", new NbtTag.NbtString("test:v1"))
                .put("Size", new NbtTag.NbtIntArray(new int[]{1, 1, 1}))
                .put("Anchor", new NbtTag.NbtIntArray(new int[]{0, 0, 0}))
                .put("Palette", palette)
                .put("Blocks", new NbtTag.NbtIntArray(new int[]{0, 0, 0, 0}));
        try (DataOutputStream output = new DataOutputStream(Files.newOutputStream(file))) {
            output.writeInt(StructureSerializer.MAGIC);
            output.writeShort(1);
            output.writeByte(1);
            GZIPOutputStream gzip = new GZIPOutputStream(output);
            NbtWriter.write(new DataOutputStream(gzip), "VoxelStructure", root);
            gzip.finish();
        }

        StructureTemplate restored = StructureSerializer.read(file, Identifier.of("test:v1"));
        assertEquals(Blocks.STONE, restored.cells().getFirst().state());
        assertNull(restored.cells().getFirst().blockEntity());
    }

    @Test
    void schematicAirCanBeIgnoredOrMadeExplicit(@TempDir Path temp) throws Exception {
        Path schematic = temp.resolve("semantic.schem");
        NbtCompound palette = new NbtCompound()
                .put("minecraft:stone", new NbtTag.NbtInt(0))
                .put("minecraft:air", new NbtTag.NbtInt(1))
                .put("minecraft:structure_void", new NbtTag.NbtInt(2));
        NbtCompound root = new NbtCompound()
                .put("Version", new NbtTag.NbtInt(2))
                .put("Width", new NbtTag.NbtShort((short) 3))
                .put("Height", new NbtTag.NbtShort((short) 1))
                .put("Length", new NbtTag.NbtShort((short) 1))
                .put("PaletteMax", new NbtTag.NbtInt(3))
                .put("Palette", palette)
                .put("BlockData", new NbtTag.NbtByteArray(new byte[]{0, 1, 2}))
                .put("BlockEntities", blockEntities(1));
        try (OutputStream file = Files.newOutputStream(schematic);
             GZIPOutputStream gzip = new GZIPOutputStream(file);
             DataOutputStream output = new DataOutputStream(gzip)) {
            NbtWriter.write(output, "Schematic", root);
        }
        SchematicImporter importer = SchematicImporter.createDefault();
        var naturalResult = importer.importFile(schematic, Identifier.of("test:natural"),
                SchematicImporter.Options.NATURAL_FEATURE);
        var natural = naturalResult.template();
        assertEquals(1, natural.cells().size());
        assertTrue(naturalResult.warnings().stream().anyMatch(warning -> warning.contains("BlockEntities")));
        var building = importer.importFile(schematic, Identifier.of("test:building"),
                new SchematicImporter.Options(true, SchematicImporter.UnknownBlocks.ERROR)).template();
        assertEquals(2, building.cells().size());
        assertTrue(building.hasExplicitAir());
    }

    private static NbtList blockEntities(int count) {
        NbtList list = new NbtList((byte) 10);
        for (int i = 0; i < count; i++) list.add(new NbtCompound());
        return list;
    }

    @Test
    void legacySchematicImportsClassicIdsMetadataAndWorldEditAnchor(@TempDir Path temp) throws Exception {
        Path schematic = temp.resolve("legacy.schematic");
        NbtCompound root = new NbtCompound()
                .put("Width", new NbtTag.NbtShort((short) 2))
                .put("Height", new NbtTag.NbtShort((short) 1))
                .put("Length", new NbtTag.NbtShort((short) 1))
                .put("Blocks", new NbtTag.NbtByteArray(new byte[]{17, 18}))
                .put("Data", new NbtTag.NbtByteArray(new byte[]{4, 0}))
                .put("WEOffsetX", new NbtTag.NbtInt(-1));
        try (OutputStream file = Files.newOutputStream(schematic);
             GZIPOutputStream gzip = new GZIPOutputStream(file);
             DataOutputStream output = new DataOutputStream(gzip)) {
            NbtWriter.write(output, "Schematic", root);
        }

        var result = new LegacySchematicImporter(BlockMapper.loadDefault()).importFile(
                schematic, Identifier.of("test:legacy"), SchematicImporter.Options.NATURAL_FEATURE);
        assertEquals(2, result.template().cells().size());
        assertEquals(1, result.template().anchorX());
        assertEquals("voxel_stories:oak_log", Blocks.getState(result.template().cells().get(0).state())
                .getBlock().getIdentifier().toString());
        assertEquals("voxel_stories:oak_leaves", Blocks.getState(result.template().cells().get(1).state())
                .getBlock().getIdentifier().toString());
    }

    @Test
    void legacySchematicReportsUnknownClassicIds(@TempDir Path temp) throws Exception {
        Path schematic = temp.resolve("unknown.schematic");
        NbtCompound root = new NbtCompound()
                .put("Width", new NbtTag.NbtShort((short) 1))
                .put("Height", new NbtTag.NbtShort((short) 1))
                .put("Length", new NbtTag.NbtShort((short) 1))
                .put("Blocks", new NbtTag.NbtByteArray(new byte[]{(byte) 250}))
                .put("Data", new NbtTag.NbtByteArray(new byte[]{0}));
        try (OutputStream file = Files.newOutputStream(schematic);
             GZIPOutputStream gzip = new GZIPOutputStream(file);
             DataOutputStream output = new DataOutputStream(gzip)) {
            NbtWriter.write(output, "Schematic", root);
        }

        IOException error = assertThrows(IOException.class,
                () -> new LegacySchematicImporter(BlockMapper.loadDefault()).importFile(
                        schematic, Identifier.of("test:unknown"), SchematicImporter.Options.NATURAL_FEATURE));
        assertTrue(error.getMessage().contains("250:0"));
    }

    @Test
    void bundledSpruceTemplatesAreNativeAndContainNoCuttingAir() throws Exception {
        String[] names = {"big_spruce_3", "large_spruce_tree_1", "large_spruce_tree_2",
                "spruce_tree_big_01", "spruce_tree_big_02", "spruce_tree_mid_wide_01"};
        for (String name : names) {
            StructureTemplate template = StructureTemplateManager.loadResource(
                    Identifier.of("skyengine:trees/spruce/" + name));
            assertNotNull(template, name);
            assertFalse(template.hasExplicitAir(), name);
            assertTrue(template.cells().size() > 100, name);
        }
    }
}
