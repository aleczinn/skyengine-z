package de.skyengine.game.world.lod;

import de.skyengine.game.world.block.Blocks;
import de.skyengine.test.BlocksTestBootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
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
