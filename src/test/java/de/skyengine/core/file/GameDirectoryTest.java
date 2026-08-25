package de.skyengine.core.file;

import de.skyengine.core.SkyEngine;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

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
    void migrationCopiesRecursivelyWithoutOverwritingTarget(@TempDir Path temp) throws Exception {
        Path source = temp.resolve("old"), target = temp.resolve("new");
        Files.createDirectories(source.resolve("saves/world"));
        Files.createDirectories(target.resolve("saves/world"));
        Files.writeString(source.resolve("saves/world/level.dat"), "old");
        Files.writeString(source.resolve("config.json"), "copied");
        Files.writeString(target.resolve("saves/world/level.dat"), "new");

        GameDirectory.copyRecursive(source, target);

        assertEquals("new", Files.readString(target.resolve("saves/world/level.dat")));
        assertEquals("copied", Files.readString(target.resolve("config.json")));
    }
}
