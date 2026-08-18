package de.skyengine.game.world.lod;

import de.skyengine.game.world.block.Blocks;
import de.skyengine.game.world.chunk.Chunk;
import de.skyengine.game.world.chunk.ChunkSection;
import de.skyengine.game.world.chunk.palette.PalettedContainer;
import de.skyengine.test.BlocksTestBootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class LodCacheStoreTest {

    @BeforeAll
    static void bootstrapBlocks() {
        BlocksTestBootstrap.ensureBootstrapped();
    }

    @Test
    void hierarchicalLevelsSurviveCoalescedBackgroundRoundTrip(@TempDir File directory) {
        Chunk chunk = new Chunk(2, -3);
        chunk.installSection(0, new ChunkSection(new PalettedContainer(
                ChunkSection.VOLUME, Blocks.STONE)));
        ChunkLodColumns expected = ChunkLodColumns.fromChunk(chunk, 1);

        try (LodCacheStore store = new LodCacheStore(directory, 7, 11)) {
            store.writeLater(chunk.chunkX, chunk.chunkZ, expected);
            store.writeLater(chunk.chunkX, chunk.chunkZ, expected);
            assertEquals(0, store.droppedWrites());
        }

        try (LodCacheStore store = new LodCacheStore(directory, 7, 11)) {
            ChunkLodColumns actual = store.read(chunk.chunkX, chunk.chunkZ);
            assertNotNull(actual);
            assertTrue(actual.hasLevel(1));
            assertFalse(actual.hasLevel(2));
            for (int level = 1; level < ChunkLodColumns.LEVELS; level++) {
                assertTrue(actual.materializeLevel(level));
                assertEquals(Blocks.STONE,
                        LodColumn.state(actual.get(0, 0, 1 << level).interval(0)));
            }
        }
    }
}
