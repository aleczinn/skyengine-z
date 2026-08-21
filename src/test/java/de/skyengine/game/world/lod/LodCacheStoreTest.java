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
import java.util.Arrays;

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

    @Test
    void groundedLandmarkFlagSurvivesLod10RoundTrip(@TempDir File directory) {
        LodColumn[][] levels = new LodColumn[ChunkLodColumns.LEVELS][];
        levels[1] = new LodColumn[16 * 16];
        Arrays.fill(levels[1], LodColumn.EMPTY);
        levels[1][0] = new LodColumn(new long[]{
                LodColumn.pack(Blocks.STONE, 0, 64, LodColumn.FLAG_TERRAIN, 4),
                LodColumn.pack(Blocks.OAK_LOG, 64, 80,
                        LodColumn.FLAG_LANDMARK | LodColumn.FLAG_SUPPORT, 1)});
        ChunkLodColumns expected = ChunkLodColumns.fromLevels(levels);

        try (LodCacheStore store = new LodCacheStore(directory, 7, 12)) {
            store.writeLater(0, 0, expected);
        }
        try (LodCacheStore store = new LodCacheStore(directory, 7, 12)) {
            ChunkLodColumns actual = store.read(0, 0);
            assertNotNull(actual);
            LodColumn column = actual.get(0, 0, 2);
            assertTrue(LodColumn.support(column.interval(1)));
            assertEquals(64, LodColumn.minY(column.interval(1)));
            assertEquals(80, LodColumn.maxY(column.interval(1)));
        }
    }
}
