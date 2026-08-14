package de.skyengine.game.world.lod;

import de.skyengine.game.world.block.Blocks;
import de.skyengine.game.world.chunk.Chunk;
import de.skyengine.game.world.generator.WorldGenerator;
import de.skyengine.test.BlocksTestBootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class LodColumnReducerTest {

    @BeforeAll
    static void bootstrapBlocks() {
        BlocksTestBootstrap.ensureBootstrapped();
    }

    @Test
    void preservesBottomTopAndFloatingLandmarksWhenLimited() {
        List<Long> runs = new ArrayList<>();
        for (int i = 0; i < 7; i++) runs.add(LodColumn.pack(10 + i, i * 10, i * 10 + 3, 0));

        LodColumn reduced = LodColumnReducer.limitIntervals(runs);

        assertEquals(LodColumn.MAX_INTERVALS, reduced.size());
        assertEquals(10, LodColumn.state(reduced.interval(0)));
        assertEquals(16, LodColumn.state(reduced.interval(reduced.size() - 1)));
    }

    @Test
    void openPitAirWinsAtQuarterCoverageButClosedCaveDoesNot() {
        LodColumn terrain = column(1, 0, 100);
        LodColumn pit = column(1, 0, 40);
        LodColumn closedCave = new LodColumn(new long[]{
                LodColumn.pack(1, 0, 40, 0), LodColumn.pack(1, 60, 100, 0)});

        LodColumn open = LodColumnReducer.reduce(new LodColumn[]{pit, terrain, terrain, terrain});
        LodColumn closed = LodColumnReducer.reduce(new LodColumn[]{closedCave, terrain, terrain, terrain});

        assertEquals(40, LodColumn.maxY(open.interval(0)));
        assertEquals(100, LodColumn.maxY(closed.interval(0)));
    }

    @Test
    void landmarkResultIsIndependentOfBoundaryDirectionAndJobOrder() {
        LodColumn terrain = column(1, 0, 64);
        LodColumn landmark = new LodColumn(new long[]{
                LodColumn.pack(1, 0, 64, 0), LodColumn.pack(2, 90, 96, LodColumn.FLAG_LANDMARK)});

        LodColumn west = LodColumnReducer.reduce(new LodColumn[]{landmark, terrain, terrain, terrain});
        LodColumn east = LodColumnReducer.reduce(new LodColumn[]{terrain, terrain, terrain, landmark});

        assertArrayEquals(west.copyIntervals(), east.copyIntervals());
        assertTrue(LodColumn.landmark(west.interval(west.size() - 1)));
    }

    @Test
    void weightedLandmarkThresholdRemovesSinglesButKeepsCoveredSilhouettes() {
        LodColumn terrain = new LodColumn(new long[]{LodColumn.pack(1, 0, 64, 0, 16)});
        LodColumn single = new LodColumn(new long[]{
                LodColumn.pack(1, 0, 64, 0, 16),
                LodColumn.pack(2, 90, 96, LodColumn.FLAG_LANDMARK, 1)});
        LodColumn covered = new LodColumn(new long[]{
                LodColumn.pack(1, 0, 64, 0, 16),
                LodColumn.pack(2, 90, 96, LodColumn.FLAG_LANDMARK, 4)});

        LodColumn sparse = LodColumnReducer.reduce(
                new LodColumn[]{single, terrain, terrain, terrain}, 64);
        LodColumn dense = LodColumnReducer.reduce(
                new LodColumn[]{covered, terrain, terrain, terrain}, 64);

        assertEquals(1, sparse.size());
        assertEquals(2, dense.size());
        assertEquals(4, LodColumn.coverage(dense.interval(1)));
        assertTrue(LodColumn.landmark(dense.interval(1)));
    }

    @Test
    void coverageRoundTripsAcrossPackedInterval() {
        long interval = LodColumn.pack(3, 10, 20,
                LodColumn.FLAG_LANDMARK | LodColumn.FLAG_SKY_OPEN, 1024);

        assertEquals(1024, LodColumn.coverage(interval));
        assertEquals(LodColumn.FLAG_LANDMARK | LodColumn.FLAG_SKY_OPEN, LodColumn.flags(interval));
    }

    @Test
    void naturalShellClosesCavesButPreservesOpenPitsAndLandmarks() {
        WorldGenerator generator = flatGenerator();
        LodColumn cave = new LodColumn(new long[]{
                LodColumn.pack(Blocks.BEDROCK, 0, 1, 0),
                LodColumn.pack(Blocks.STONE, 1, 30, 0),
                LodColumn.pack(Blocks.STONE, 40, 64, 0)});
        LodColumn pit = new LodColumn(new long[]{
                LodColumn.pack(Blocks.BEDROCK, 0, 1, 0),
                LodColumn.pack(Blocks.STONE, 1, 30, 0)});
        LodColumn landmark = new LodColumn(new long[]{
                LodColumn.pack(Blocks.BEDROCK, 0, 1, 0),
                LodColumn.pack(Blocks.STONE, 1, 64, 0),
                LodColumn.pack(Blocks.OAK_PLANKS, 90, 96, LodColumn.FLAG_LANDMARK)});

        LodColumn closed = ChunkLodColumns.normalizeNaturalShell(cave, generator, 0, 0, 1);
        LodColumn open = ChunkLodColumns.normalizeNaturalShell(pit, generator, 0, 0, 1);
        LodColumn preserved = ChunkLodColumns.normalizeNaturalShell(landmark, generator, 0, 0, 1);

        assertEquals(2, closed.size());
        assertEquals(1, LodColumn.minY(closed.interval(1)));
        assertEquals(64, LodColumn.maxY(closed.interval(1)));
        assertTrue(LodColumn.terrain(closed.interval(1)));
        assertEquals(30, LodColumn.maxY(open.interval(open.size() - 1)));
        assertEquals(96, LodColumn.maxY(preserved.interval(preserved.size() - 1)));
        assertTrue(LodColumn.landmark(preserved.interval(preserved.size() - 1)));
    }

    @Test
    void naturalShellExtendsExistingWaterDownToTheSolidSurface() {
        LodColumn floatingWater = new LodColumn(new long[]{
                LodColumn.pack(Blocks.BEDROCK, 0, 1, 0),
                LodColumn.pack(Blocks.STONE, 1, 40, 0),
                LodColumn.pack(Blocks.WATER, 45, 65, LodColumn.FLAG_SKY_OPEN)});

        LodColumn normalized = ChunkLodColumns.normalizeNaturalShell(
                floatingWater, flatGenerator(), 0, 0, 1);

        long water = normalized.interval(normalized.size() - 1);
        assertEquals(Blocks.WATER, LodColumn.state(water));
        assertEquals(40, LodColumn.minY(water));
        assertEquals(65, LodColumn.maxY(water));
    }

    private static WorldGenerator flatGenerator() {
        return new WorldGenerator(123) {
            @Override public int sampleHeight(int x, int z) { return 63; }
            @Override public void generate(Chunk chunk) {}
            @Override public int lodWorldBottomState() { return Blocks.BEDROCK; }
            @Override public long sampleGroundSurface(int x, int z) {
                return LodDataSource.pack(Blocks.STONE, 63);
            }
        };
    }

    private static LodColumn column(int state, int minY, int maxY) {
        return new LodColumn(new long[]{LodColumn.pack(state, minY, maxY, 0)});
    }
}
