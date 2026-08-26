package de.skyengine.game.world;

import de.skyengine.game.world.block.entity.DataTag;
import de.skyengine.game.world.save.LevelData;
import de.skyengine.game.world.save.PlayerIO;
import de.skyengine.game.world.save.WorldSaves;
import de.skyengine.game.world.dimension.WorldgenRegistries;
import de.skyengine.test.BlocksTestBootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class PlayerManagerTest {

    @BeforeAll
    static void bootstrap() {
        BlocksTestBootstrap.ensureBootstrapped();
    }

    @Test
    void migratesLegacyPlayerToUuidFileWithoutDeletingBackup(@TempDir Path root) {
        UUID uuid = UUID.randomUUID();
        DataTag legacy = new DataTag()
                .putLong("uuidMost", uuid.getMostSignificantBits())
                .putLong("uuidLeast", uuid.getLeastSignificantBits())
                .putDouble("x", 12.5)
                .putDouble("y", 70.0)
                .putDouble("z", -8.5)
                .putString("dimension", "skyengine:overworld")
                .putInt("selectedSlot", 4);
        PlayerIO.write(PlayerIO.legacyPlayerFile(root.toFile()), legacy);

        LevelData level = new LevelData();
        level.name = "Migration";
        level.seed = 7;
        PlayerManager players = new PlayerManager(new WorldSaves.WorldSave("migration", level),
                root.toFile());

        assertEquals(uuid, players.localPlayer().getUuid());
        assertEquals(uuid.toString(), level.localPlayerUuid);
        assertEquals(12.5, players.localPlayer().x);
        assertEquals(4, players.localPlayer().getSelectedSlot());
        assertTrue(Files.isRegularFile(PlayerIO.legacyPlayerFile(root.toFile()).toPath()));
        assertTrue(Files.isRegularFile(PlayerIO.playerFile(root.toFile(), uuid).toPath()));

        players.localPlayer().setSelectedSlot(6);
        players.saveAll();
        assertEquals(6, PlayerIO.read(PlayerIO.playerFile(root.toFile(), uuid))
                .getInt("selectedSlot", -1));
    }

    @Test
    void persistsDimensionCapableHome(@TempDir Path root) {
        LevelData level = new LevelData();
        level.name = "Home";
        level.seed = 19;
        WorldSaves.WorldSave save = new WorldSaves.WorldSave("home", level);
        PlayerManager first = new PlayerManager(save, root.toFile());
        first.localPlayer().setHome(new PlayerLocation(WorldgenRegistries.MINING,
                12.25, 67, -3.75, 90, -12));
        first.saveAll();

        PlayerManager loaded = new PlayerManager(save, root.toFile());

        assertEquals(new PlayerLocation(WorldgenRegistries.MINING,
                12.25, 67, -3.75, 90, -12), loaded.localPlayer().getHome());
    }

    @Test
    void newPlayerStartsInConfiguredSpawnDimension(@TempDir Path root) {
        LevelData level = new LevelData();
        level.name = "Spawn";
        level.seed = 23;
        level.spawnDimension = WorldgenRegistries.MINING.toString();
        level.spawnX = 8;
        level.spawnY = 70;
        level.spawnZ = -4;

        PlayerManager players = new PlayerManager(new WorldSaves.WorldSave("spawn", level), root.toFile());

        assertEquals(WorldgenRegistries.MINING, players.localPlayer().getDimensionId());
    }

    @Test
    void worldSpawnPersistsYawAndPitchAndOldSavesDefaultToZero(@TempDir Path root) {
        LevelData level = new LevelData();
        level.name = "Spawn orientation";
        level.seed = 31;
        WorldSaves.WorldSave save = new WorldSaves.WorldSave("spawn_orientation", level);
        World world = new World(save, root.toFile(), null);
        try {
            assertEquals(null, world.spawnPoint());
            world.setSpawnPoint(WorldgenRegistries.MINING, 12, 73, -9, 135F, -22.5F);
            assertEquals(new World.SpawnPoint(WorldgenRegistries.MINING,
                    12, 73, -9, 135F, -22.5F), world.spawnPoint());

            level.spawnYaw = null;
            level.spawnPitch = null;
            assertEquals(new World.SpawnPoint(WorldgenRegistries.MINING,
                    12, 73, -9, 0F, 0F), world.spawnPoint());
        } finally {
            world.dispose();
        }
    }
}
