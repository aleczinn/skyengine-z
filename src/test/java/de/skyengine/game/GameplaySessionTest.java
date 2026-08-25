package de.skyengine.game;

import de.skyengine.game.world.World;
import de.skyengine.game.world.dimension.WorldgenRegistries;
import de.skyengine.game.world.save.LevelData;
import de.skyengine.game.world.save.WorldSaves;
import de.skyengine.test.BlocksTestBootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

final class GameplaySessionTest {

    @BeforeAll
    static void bootstrap() {
        BlocksTestBootstrap.ensureBootstrapped();
    }

    @Test
    void switchingDimensionKeepsWorldPlayerAndSavegameRuntime(@TempDir Path root) {
        LevelData level = new LevelData();
        level.name = "Session";
        level.seed = 123;
        World world = new World(new WorldSaves.WorldSave("session", level), root.toFile(), null);
        GameplaySession session = new GameplaySession(world);
        try {
            Object transfer = new Object();
            var player = session.player();

            session.switchDimension(WorldgenRegistries.MINING, transfer);

            assertSame(world, session.world());
            assertSame(player, session.player());
            assertEquals(WorldgenRegistries.MINING, session.dimension().getDimensionId());
            assertEquals(WorldgenRegistries.MINING, player.getDimensionId());
            assertEquals(2, world.dimensions().loadedCount(),
                    "die Quelldimension bleibt waehrend der Grace Period geladen");
        } finally {
            session.dispose();
        }
    }
}
