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
    void createsCurrentOverworldMetadata(@TempDir Path saveRoot) {
        LevelData level = new LevelData();
        level.seed = 12345;

        DimensionSaves.Resolved resolved = DimensionSaves.resolve(
                saveRoot.toFile(), level, WorldgenRegistries.OVERWORLD);

        assertEquals(saveRoot.toAbsolutePath().normalize(),
                resolved.root().toPath().toAbsolutePath().normalize());
        assertEquals(12345, resolved.data().seed);
        assertEquals(WorldgenRegistries.ALPHA_V2.toString(), resolved.data().generator);
        assertTrue(level.dimensions.containsKey(WorldgenRegistries.OVERWORLD.toString()));
    }

    @Test
    void createsMiningDimensionWithDerivedSeedAndNamespacedPath(@TempDir Path saveRoot) {
        LevelData level = new LevelData();
        level.seed = 987654321;

        DimensionSaves.Resolved resolved = DimensionSaves.resolve(
                saveRoot.toFile(), level, WorldgenRegistries.MINING);

        assertEquals(WorldgenRegistries.MINING_FLAT_V1.toString(), resolved.data().generator);
        assertNotEquals(level.seed, resolved.data().seed);
        assertEquals("voxelstories", SkyEngine.GAME_PREFIX);
        assertEquals(saveRoot.resolve("dimensions/voxelstories/mining").toAbsolutePath().normalize(),
                resolved.root().toPath().toAbsolutePath().normalize());
        assertTrue(resolved.regionDir().toPath().startsWith(resolved.root().toPath()));
        assertTrue(resolved.lodDir().toPath().startsWith(resolved.root().toPath()));
    }

    @Test
    void createsNetherWithOwnGeneratorAndEnvironment(@TempDir Path saveRoot) {
        LevelData level = new LevelData();
        level.seed = 24680;

        DimensionSaves.Resolved resolved = DimensionSaves.resolve(
                saveRoot.toFile(), level, WorldgenRegistries.NETHER);
        DimensionDefinition definition = WorldgenRegistries.DIMENSIONS.get(WorldgenRegistries.NETHER);

        assertEquals(WorldgenRegistries.NETHER_V1.toString(), resolved.data().generator);
        assertEquals(saveRoot.resolve("dimensions/voxelstories/nether").toAbsolutePath().normalize(),
                resolved.root().toPath().toAbsolutePath().normalize());
        assertTrue(!definition.lodAllowed());
        assertTrue(!definition.environment().hasSkylight());
        assertTrue(definition.environment().forceFog());
        assertEquals(8.0, definition.environment().coordinateScale());
    }
}
