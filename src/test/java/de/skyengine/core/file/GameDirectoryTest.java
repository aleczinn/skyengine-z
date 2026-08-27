package de.skyengine.core.file;

import de.skyengine.core.SkyEngine;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class GameDirectoryTest {
    @Test
    void gradleTestsUseAnIsolatedGameDirectory() {
        String configured = System.getProperty(GameDirectory.ROOT_PROPERTY);
        assertNotNull(configured, "Der Testprozess muss einen isolierten Spielordner konfigurieren");
        assertEquals(Path.of(configured).toAbsolutePath().normalize(),
                GameDirectory.root().toPath().toAbsolutePath().normalize());
        assertFalse(Files.exists(GameDirectory.root().toPath().resolve(".migration-from-skyengine-v1")));
    }

    @Test
    void dataFolderIsDerivedWithoutSeparators() {
        assertEquals(".voxelstories", SkyEngine.GAME_DATA_DIRECTORY_NAME);
    }

    @Test
    void obsoleteMarkersAreRemovedWithoutTouchingOtherHiddenFiles(@TempDir Path temp) throws Exception {
        Files.createDirectories(temp.resolve("bin/structures"));
        Files.writeString(temp.resolve(".migration-from-skyengine-v1"), "completed");
        Files.writeString(temp.resolve(".migration-from-working-directory-v1"), "completed");
        Files.writeString(temp.resolve("bin/structures/.default-structure-v1"), "version=1");
        Files.writeString(temp.resolve("bin/structures/.default-structures-v1"), "version=1");
        Files.writeString(temp.resolve(".keep-me"), "data");

        GameDirectory.cleanupObsoleteMarkers(temp);

        assertFalse(Files.exists(temp.resolve(".migration-from-skyengine-v1")));
        assertFalse(Files.exists(temp.resolve(".migration-from-working-directory-v1")));
        assertFalse(Files.exists(temp.resolve("bin/structures/.default-structure-v1")));
        assertFalse(Files.exists(temp.resolve("bin/structures/.default-structures-v1")));
        assertTrue(Files.isRegularFile(temp.resolve(".keep-me")));
    }
}
