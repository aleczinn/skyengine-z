package de.skyengine.game.world.structure;

import de.skyengine.game.world.block.Blocks;
import de.skyengine.game.world.block.Identifier;
import de.skyengine.mcimport.nbt.NbtCompound;
import de.skyengine.mcimport.nbt.NbtTag;
import de.skyengine.mcimport.nbt.NbtWriter;
import de.skyengine.test.BlocksTestBootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.DataOutputStream;
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
                .put("BlockData", new NbtTag.NbtByteArray(new byte[]{0, 1, 2}));
        try (OutputStream file = Files.newOutputStream(schematic);
             GZIPOutputStream gzip = new GZIPOutputStream(file);
             DataOutputStream output = new DataOutputStream(gzip)) {
            NbtWriter.write(output, "Schematic", root);
        }
        SchematicImporter importer = SchematicImporter.createDefault();
        var natural = importer.importFile(schematic, Identifier.of("test:natural"),
                SchematicImporter.Options.NATURAL_FEATURE).template();
        assertEquals(1, natural.cells().size());
        var building = importer.importFile(schematic, Identifier.of("test:building"),
                new SchematicImporter.Options(true, SchematicImporter.UnknownBlocks.ERROR)).template();
        assertEquals(2, building.cells().size());
        assertTrue(building.hasExplicitAir());
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
