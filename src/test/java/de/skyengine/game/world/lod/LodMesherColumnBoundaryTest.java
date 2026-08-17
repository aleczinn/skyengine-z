package de.skyengine.game.world.lod;

import de.skyengine.game.world.block.Blocks;
import de.skyengine.game.world.chunk.ChunkMesher;
import de.skyengine.test.BlocksTestBootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class LodMesherColumnBoundaryTest {

    @BeforeAll
    static void bootstrapBlocks() {
        BlocksTestBootstrap.ensureBootstrapped();
    }

    @Test
    void boundaryLandmarkIsDeterministicFromBothRegionJobOrders() {
        LodDataSource source = new LodDataSource() {
            @Override public boolean hasColumns() { return true; }

            @Override
            public LodColumn sampleColumn(int x, int z, int size) {
                if (x >= 126 && x < 130 && z >= 56 && z < 72) {
                    return new LodColumn(new long[]{
                            LodColumn.pack(Blocks.STONE, 0, 64, 0),
                            LodColumn.pack(Blocks.OAK_PLANKS, 90, 96, LodColumn.FLAG_LANDMARK)});
                }
                return new LodColumn(new long[]{LodColumn.pack(Blocks.STONE, 0, 64, 0)});
            }

            @Override
            public long sampleSurface(int x, int z, int size) {
                return LodDataSource.pack(Blocks.STONE, 63);
            }
        };
        LodMesher mesher = new LodMesher();
        LodConfig config = LodConfig.of(16, 128);
        LodBlockAppearance appearance = new LodBlockAppearance();

        LodManager.LodMeshResult westFirst = mesher.mesh(source, appearance, config,
                1, 1, 0, 0, 7, 0, 64, 64);
        LodManager.LodMeshResult eastFirst = mesher.mesh(source, appearance, config,
                1, 1, 0, 0, 7, 0, 192, 64);
        LodManager.LodMeshResult eastRegion = mesher.mesh(source, appearance, config,
                1, 1, 1, 0, 7, 0, 192, 64);

        assertArrayEquals(westFirst.opaqueData(), eastFirst.opaqueData());
        assertArrayEquals(westFirst.translucentData(), eastFirst.translucentData());
        assertTrue(westFirst.maxY() >= 96F);
        assertTrue(eastRegion.maxY() >= 96F);
    }

    @Test
    void negativeRegionCoordinatesUseTheSameCanonicalCells() {
        LodDataSource source = constantColumns();
        LodConfig config = LodConfig.of(16, 128);
        LodBlockAppearance appearance = new LodBlockAppearance();

        LodManager.LodMeshResult first = new LodMesher().mesh(source, appearance, config,
                2, 1, -1, -1, 3, 0, -64, -64);
        LodManager.LodMeshResult second = new LodMesher().mesh(source, appearance, config,
                2, 1, -1, -1, 3, 0, -192, -192);

        assertArrayEquals(first.opaqueData(), second.opaqueData());
    }

    @Test
    void dynamicL0MaskBoundaryGetsADeepGreedySkirtEvenWhenSamplesAreFlush() {
        LodDataSource source = new LodDataSource() {
            @Override public boolean hasColumns() { return true; }

            @Override
            public LodColumn sampleColumn(int x, int z, int size) {
                return new LodColumn(new long[]{
                        LodColumn.pack(Blocks.BEDROCK, 0, 1, LodColumn.FLAG_TERRAIN),
                        LodColumn.pack(Blocks.STONE, 40, 64, LodColumn.FLAG_TERRAIN)});
            }

            @Override public long sampleSurface(int x, int z, int size) {
                return LodDataSource.pack(Blocks.STONE, 63);
            }
        };

        /* Chunk (1,0) ist L0 und wird aus dem LOD-Mesh geclippt. Die sichtbare LOD-Zelle
           westlich davon braucht bei x=32 trotz identischer Samples einen 32 Blöcke tiefen
           L1-Skirt (64 -> 32). */
        LodManager.LodMeshResult result = new LodMesher().mesh(source, new LodBlockAppearance(),
                LodConfig.of(16, 128), 1, 1, 0, 0, 0, 1 << 1, 64, 64);

        assertEquals(1, countVerticalQuads(result.opaqueData(), result.yBase(), 32F, 32F, 64F),
                "Der tiefe Masken-Skirt muss vorhanden und entlang der Chunkkante greedy gemergt sein");
    }

    private static int countVerticalQuads(int[] data, int yBase, float expectedX,
                                          float expectedMinY, float expectedMaxY) {
        int matches = 0;
        for (int q = 0; q < data.length; q += 4 * ChunkMesher.VERTEX_SIZE) {
            boolean constantX = true;
            float minY = Float.POSITIVE_INFINITY, maxY = Float.NEGATIVE_INFINITY;
            for (int v = 0; v < 4; v++) {
                int packed = data[q + v * ChunkMesher.VERTEX_SIZE];
                float x = coordinate(packed & 0xFFFF);
                float y = coordinate((packed >>> 16) & 0xFFFF) + yBase;
                constantX &= Math.abs(x - expectedX) <= 0.01F;
                minY = Math.min(minY, y);
                maxY = Math.max(maxY, y);
            }
            if (constantX && Math.abs(minY - expectedMinY) <= 0.01F
                    && Math.abs(maxY - expectedMaxY) <= 0.01F) matches++;
        }
        return matches;
    }

    private static float coordinate(int packed) {
        return packed / LodMesher.posScaleFor(1) - 1F;
    }

    private static LodDataSource constantColumns() {
        return new LodDataSource() {
            @Override public boolean hasColumns() { return true; }
            @Override public LodColumn sampleColumn(int x, int z, int size) {
                return new LodColumn(new long[]{LodColumn.pack(Blocks.STONE, 0, 64, 0)});
            }
            @Override public long sampleSurface(int x, int z, int size) {
                return LodDataSource.pack(Blocks.STONE, 63);
            }
        };
    }
}
