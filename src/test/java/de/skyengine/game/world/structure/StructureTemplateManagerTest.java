package de.skyengine.game.world.structure;

import de.skyengine.game.world.block.Blocks;
import de.skyengine.game.world.block.Identifier;
import de.skyengine.test.BlocksTestBootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

final class StructureTemplateManagerTest {
    @BeforeAll static void bootstrap() { BlocksTestBootstrap.ensureBootstrapped(); }

    @Test
    void storesGloballyWithoutNamespaceDirectoryAndSnapshotsAreStable(@TempDir Path temp) throws Exception {
        Path structures = temp.resolve("bin/structures");
        StructureTemplateManager manager = new StructureTemplateManager(structures, temp.resolve("saves").toFile());
        Identifier id = Identifier.of("mod:trees/oak/oak_1");
        StructureTemplate first = template(id, Blocks.STONE);
        manager.saveAuthored(first, false);

        Path expected = structures.resolve("trees/oak/oak_1.structure");
        assertTrue(Files.isRegularFile(expected));
        assertFalse(Files.exists(structures.resolve("mod")));
        assertEquals(first.fingerprint(), manager.get(id).fingerprint());

        StructureTemplateManager.Snapshot snapshot = manager.snapshot();
        StructureTemplate second = template(id, Blocks.DIRT);
        manager.saveAuthored(second, true);
        assertEquals(first.fingerprint(), snapshot.get(id).fingerprint());
        assertEquals(second.fingerprint(), manager.get(id).fingerprint());
    }

    @Test
    void rejectsDifferentNamespaceUsingSamePath(@TempDir Path temp) throws Exception {
        StructureTemplateManager manager = new StructureTemplateManager(temp.resolve("structures"), temp.toFile());
        manager.saveAuthored(template(Identifier.of("one:trees/oak"), Blocks.STONE), false);
        assertThrows(java.io.IOException.class, () ->
                manager.saveAuthored(template(Identifier.of("two:trees/oak"), Blocks.STONE), true));
    }

    @Test
    void runtimeCatalogOnlyListsExternalFiles(@TempDir Path temp) throws Exception {
        Path structures = temp.resolve("structures");
        StructureTemplateManager manager = new StructureTemplateManager(structures, temp.toFile());
        assertTrue(manager.ids().isEmpty());
        assertTrue(manager.references().isEmpty());

        Identifier id = Identifier.of("skyengine:trees/oak/oak_1");
        manager.saveAuthored(template(id, Blocks.STONE), false);
        assertEquals(List.of("trees/oak/oak_1.structure"), manager.references());
        assertEquals(id, manager.get("trees/oak/oak_1.structure").id());
        assertEquals(id, manager.get("skyengine:trees/oak/oak_1").id());
    }

    @Test
    void defaultInstallerCopiesOnceAndNeverOverwrites(@TempDir Path temp) throws Exception {
        Path structures = temp.resolve("structures");
        DefaultStructureInstaller.install(structures);
        Path installed = structures.resolve("trees/spruce/big_spruce_3.structure");
        assertTrue(Files.isRegularFile(installed));
        assertTrue(Files.isRegularFile(structures.resolve(".default-structures-v1")));

        byte[] custom = {1, 2, 3, 4};
        Files.write(installed, custom);
        DefaultStructureInstaller.install(structures);
        assertArrayEquals(custom, Files.readAllBytes(installed));
    }

    private static StructureTemplate template(Identifier id, int state) {
        return new StructureTemplate(id, 1, 1, 1, 0, 0, 0,
                List.of(new StructureTemplate.Cell(0, 0, 0, state)));
    }
}
