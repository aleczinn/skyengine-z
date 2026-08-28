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
    }

    @Test
    void dataFolderIsDerivedWithoutSeparators() {
        assertEquals(".voxelstories", SkyEngine.GAME_DATA_DIRECTORY_NAME);
    }

}
