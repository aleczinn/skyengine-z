package de.skyengine.game.world;

import de.skyengine.game.world.chunk.WorldWorkerPool;
import de.skyengine.game.world.dimension.PortalLinks;
import de.skyengine.game.world.dimension.WorldgenRegistries;
import de.skyengine.game.world.save.LevelData;
import de.skyengine.test.BlocksTestBootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

final class DimensionManagerTest {

    @BeforeAll
    static void bootstrap() {
        BlocksTestBootstrap.ensureBootstrapped();
    }

    @Test
    void ticketsShareOneInstanceAndUnloadOnlyAfterGracePeriod(@TempDir Path root) {
        LevelData level = new LevelData();
        level.seed = 42;
        AtomicLong clock = new AtomicLong();
        WorldWorkerPool workers = new WorldWorkerPool(1);
        DimensionManager manager = new DimensionManager("test", level, root.toFile(), workers,
                new PortalLinks(root.toFile()), null,
                clock::get, 30_000_000_000L);
        try {
            DimensionManager.DimensionTicket player = manager.acquire(WorldgenRegistries.OVERWORLD,
                    DimensionManager.TicketType.PLAYER, "player");
            DimensionManager.DimensionTicket forced = manager.acquire(WorldgenRegistries.OVERWORLD,
                    DimensionManager.TicketType.FORCED, "spawn");

            assertSame(player.dimension(), forced.dimension());
            assertEquals(1, manager.loadedCount());

            player.close();
            clock.addAndGet(60_000_000_000L);
            manager.tickLifecycle();
            assertEquals(1, manager.loadedCount(), "ein weiteres Ticket haelt die Dimension");

            forced.close();
            clock.addAndGet(29_999_999_999L);
            manager.tickLifecycle();
            assertEquals(1, manager.loadedCount());

            clock.incrementAndGet();
            manager.tickLifecycle();
            assertEquals(0, manager.loadedCount());
        } finally {
            manager.dispose();
            workers.dispose();
        }
    }
}
