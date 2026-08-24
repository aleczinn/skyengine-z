package de.skyengine.game.world.dimension;

import de.skyengine.core.SkyEngine;
import de.skyengine.game.world.save.LevelData;
import de.skyengine.test.BlocksTestBootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class DimensionSavesTest {

    @BeforeAll
    static void bootstrap() {
        BlocksTestBootstrap.ensureBootstrapped();
    }

    @Test
    void migratesLegacyOverworldInPlace(@TempDir Path saveRoot) {
        LevelData level = new LevelData();
        level.seed = 12345;
        level.generator = "alpha_v2";
        level.generatorVersion = 1;

        DimensionSaves.Resolved resolved = DimensionSaves.resolve(
                saveRoot.toFile(), level, WorldgenRegistries.OVERWORLD);

        assertEquals(saveRoot.toAbsolutePath().normalize(),
                resolved.root().toPath().toAbsolutePath().normalize());
        assertEquals(12345, resolved.data().seed);
        assertEquals(WorldgenRegistries.ALPHA_V2.toString(), resolved.data().generator);
        assertTrue(level.dimensions.containsKey(WorldgenRegistries.OVERWORLD.toString()));
    }

    @Test
    void createsMiningDimensionWithDerivedSeedAndIsolatedPath(@TempDir Path saveRoot) {
        LevelData level = new LevelData();
        level.seed = 987654321;

        DimensionSaves.Resolved resolved = DimensionSaves.resolve(
                saveRoot.toFile(), level, WorldgenRegistries.MINING);

        assertEquals(WorldgenRegistries.MINING_FLAT_V1.toString(), resolved.data().generator);
        assertNotEquals(level.seed, resolved.data().seed);
        assertEquals("Voxel Stories", SkyEngine.GAME_NAME);
        assertEquals("voxel_stories", SkyEngine.GAME_PREFIX);
        assertEquals(saveRoot.resolve("dimensions/voxel_stories/mining").toAbsolutePath().normalize(),
                resolved.root().toPath().toAbsolutePath().normalize());
        assertTrue(resolved.regionDir().toPath().startsWith(resolved.root().toPath()));
        assertTrue(resolved.lodDir().toPath().startsWith(resolved.root().toPath()));
    }

    @Test
    void migratesPreviousFlatDimensionDirectory(@TempDir Path saveRoot) throws Exception {
        Path legacy = saveRoot.resolve("dimensions/mining");
        java.nio.file.Files.createDirectories(legacy.resolve("region"));
        java.nio.file.Files.writeString(legacy.resolve("region/marker.txt"), "bestehend");
        LevelData level = new LevelData();
        level.seed = 17;

        DimensionSaves.Resolved resolved = DimensionSaves.resolve(
                saveRoot.toFile(), level, WorldgenRegistries.MINING);

        assertEquals(saveRoot.resolve("dimensions/voxel_stories/mining").toAbsolutePath().normalize(),
                resolved.root().toPath().toAbsolutePath().normalize());
        assertTrue(java.nio.file.Files.exists(
                saveRoot.resolve("dimensions/voxel_stories/mining/region/marker.txt")));
        assertTrue(java.nio.file.Files.notExists(legacy));
    }

    @Test
    void migratesOldSkyenginePrefix(@TempDir Path saveRoot) throws Exception {
        Path legacy = saveRoot.resolve("dimensions/skyengine/mining");
        java.nio.file.Files.createDirectories(legacy.resolve("region"));
        java.nio.file.Files.writeString(legacy.resolve("region/marker.txt"), "bestehend");
        LevelData level = new LevelData();
        level.seed = 18;

        DimensionSaves.Resolved resolved = DimensionSaves.resolve(
                saveRoot.toFile(), level, WorldgenRegistries.MINING);

        assertEquals(saveRoot.resolve("dimensions/voxel_stories/mining").toAbsolutePath().normalize(),
                resolved.root().toPath().toAbsolutePath().normalize());
        assertTrue(java.nio.file.Files.exists(
                saveRoot.resolve("dimensions/voxel_stories/mining/region/marker.txt")));
        assertTrue(java.nio.file.Files.notExists(legacy));
    }
}
