package de.skyengine.game.world;

import de.skyengine.game.world.block.entity.DataTag;
import de.skyengine.game.world.save.LevelData;
import de.skyengine.game.world.save.PlayerIO;
import de.skyengine.game.world.save.WorldSaves;
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
}
