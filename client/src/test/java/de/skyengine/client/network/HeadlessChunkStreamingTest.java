package de.skyengine.client.network;

import de.skyengine.game.world.block.Blocks;
import de.skyengine.game.world.chunk.Chunk;
import de.skyengine.server.ServerConfig;
import de.skyengine.server.network.ServerSessionManager;
import de.skyengine.server.player.OfflineIdentityProvider;
import de.skyengine.server.world.HeadlessWorldRuntime;
import de.skyengine.shared.EngineInfo;
import de.skyengine.shared.network.ConnectionState;
import de.skyengine.shared.network.ProtocolLimits;
import de.skyengine.shared.network.pack.RegistryMapping;
import de.skyengine.shared.network.transport.LocalTransport;
import de.skyengine.test.BlocksTestBootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class HeadlessChunkStreamingTest {
    @TempDir Path temporaryDirectory;

    @BeforeAll static void bootstrapBlocks() {
        BlocksTestBootstrap.ensureBootstrapped();
    }

    @Test void negotiatedHeadlessTerrainReachesLegacyL0Representation() throws Exception {
        ServerConfig config = new ServerConfig(this.temporaryDirectory, "127.0.0.1", 25565,
                8, 2, 2, "world", "test", EngineInfo.TICKS_PER_SECOND, 5, 30,
                "none", 1, 1024, ProtocolLimits.MAX_FRAME_BYTES,
                ProtocolLimits.MAX_DECOMPRESSED_BYTES, 4 * 1024 * 1024,
                1200, "offline", 2);
        try (HeadlessWorldRuntime world = new HeadlessWorldRuntime(config.worldDirectory(), 2);
             ServerSessionManager server = new ServerSessionManager(config,
                     new OfflineIdentityProvider(), world)) {
            LocalTransport.Pair pair = LocalTransport.create();
            server.accept(pair.server());
            ReplicatedChunkCache chunks = new ReplicatedChunkCache(null);
            ClientNetworkSession client = new ClientNetworkSession(pair.client(), chunks,
                    packs -> ClientNetworkSession.PackValidation.acceptAll(),
                    new ClientNetworkSession.Listener() {
                        @Override public void registryReceived(RegistryMapping mapping) {
                            if (mapping.registry().equals("block_state")) {
                                chunks.setBlockStateMapper(LegacyBlockStateNetworkMapper.create(mapping));
                            }
                        }
                    });
            client.start("TerrainPlayer", null);

            long deadline = System.nanoTime() + 5_000_000_000L;
            long tick = 0;
            while (chunks.size() < 9 && System.nanoTime() < deadline) {
                server.tick(tick++, System.nanoTime());
                client.update();
                Thread.sleep(1);
            }
            assertEquals(ConnectionState.PLAY, client.state());
            assertTrue(chunks.size() >= 9);
            var center = chunks.get(HeadlessWorldRuntime.OVERWORLD, 0, 0);
            assertNotNull(center);
            Chunk decoded = LegacyChunkSnapshotDecoder.decode(center);
            int surface = center.height(0) - 1;
            assertEquals(Blocks.GRASS_BLOCK, decoded.getBlock(0, surface, 0));
            assertEquals(15, decoded.light.get(0, surface, 0));
        }
    }
}
