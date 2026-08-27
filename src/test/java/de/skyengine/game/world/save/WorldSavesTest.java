package de.skyengine.game.world.save;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

final class WorldSavesTest {
    @Test
    void incompatibleWorldIsHiddenAndNeverMutated(@TempDir Path root) throws Exception {
        Path oldWorld = root.resolve("old-world");
        Files.createDirectories(oldWorld);
        Path level = oldWorld.resolve("level.json");
        byte[] original = ("{\n  \"name\": \"Alt\",\n  \"formatVersion\": 3,\n"
                + "  \"seed\": 1,\n  \"lastPlayed\": 10\n}\n").getBytes(StandardCharsets.UTF_8);
        Files.write(level, original);

        assertEquals(0, WorldSaves.listInDirectory(root.toFile()).size());
        assertArrayEquals(original, Files.readAllBytes(level));
    }
}
