package de.skyengine.client.network;

import de.skyengine.game.world.block.Blocks;
import de.skyengine.game.world.chunk.ChunkManager;
import de.skyengine.shared.network.packets.CorePackets;
import de.skyengine.shared.world.BlockChange;
import de.skyengine.shared.world.ChunkColumnSnapshot;
import de.skyengine.shared.world.ChunkSectionSnapshot;
import de.skyengine.shared.world.LightPlane;
import de.skyengine.test.BlocksTestBootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

final class ReplicatedChunkWorldAdapterTest {
    @BeforeAll static void bootstrap() {
        BlocksTestBootstrap.ensureBootstrapped();
    }

    @Test void completedBatchesAndDeltasFeedTheExistingChunkRepresentation() throws Exception {
        ChunkManager manager = new ChunkManager(null, null, true);
        try {
            ReplicatedChunkCache cache = new ReplicatedChunkCache(
                    new ReplicatedChunkWorldAdapter("skyengine:overworld", manager));
            ChunkColumnSnapshot snapshot = snapshot(3, -2);

            cache.accept(new CorePackets.ChunkBatchStart(1, snapshot.dimension(), 3, -2, 1));
            cache.accept(new CorePackets.ChunkColumnData(1, snapshot));
            cache.accept(new CorePackets.ChunkBatchEnd(1));

            assertEquals(Blocks.STONE, manager.getChunk(3, -2).getBlock(0, 5, 0));
            cache.accept(new CorePackets.BlockUpdate(snapshot.dimension(), 3, -2, 1,
                    new BlockChange(0, 5, 0, Blocks.DIRT)));
            assertEquals(Blocks.DIRT, manager.getChunk(3, -2).getBlock(0, 5, 0));
            assertEquals(1, cache.get(snapshot.dimension(), 3, -2).revision());
            assertEquals(Blocks.DIRT, LegacyChunkSnapshotDecoder.decode(
                    cache.get(snapshot.dimension(), 3, -2)).getBlock(0, 5, 0));

            cache.accept(new CorePackets.UnloadChunk(snapshot.dimension(), 3, -2));
            assertNull(manager.getChunk(3, -2));
        } finally {
            manager.dispose();
        }
    }

    private static ChunkColumnSnapshot snapshot(int chunkX, int chunkZ) {
        LightPlane dark = new LightPlane(LightPlane.Mode.UNIFORM_ZERO, null);
        ChunkSectionSnapshot section = new ChunkSectionSnapshot(0,
                ChunkSectionSnapshot.VOLUME, new int[]{Blocks.STONE}, 0, new long[0], dark, dark);
        return new ChunkColumnSnapshot("skyengine:overworld", chunkX, chunkZ, 0,
                List.of(section), new int[32 * 32], filled(33 * 33, 0x55AA55),
                filled(33 * 33, 0x448844), new int[32 * 32]);
    }

    private static int[] filled(int length, int value) {
        int[] result = new int[length];
        java.util.Arrays.fill(result, value);
        return result;
    }
}
