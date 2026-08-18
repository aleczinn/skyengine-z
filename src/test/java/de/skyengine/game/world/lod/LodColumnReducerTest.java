package de.skyengine.game.world.lod;

import de.skyengine.game.world.block.Blocks;
import de.skyengine.game.world.chunk.Chunk;
import de.skyengine.game.world.generator.WorldGenerator;
import de.skyengine.game.world.generator.feature.ChunkDecorator;
import de.skyengine.game.world.generator.feature.Feature;
import de.skyengine.game.world.generator.feature.trees.BiomeTreeFeature;
import de.skyengine.game.world.generator.generators.AlphaWorldGeneratorV2;
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
    void terrainShellCannotBeDisplacedByThreeLandmarks() {
        LodColumn reduced = LodColumnReducer.limitIntervals(List.of(
                LodColumn.pack(Blocks.BEDROCK, 0, 1, LodColumn.FLAG_TERRAIN),
                LodColumn.pack(Blocks.STONE, 1, 138, LodColumn.FLAG_TERRAIN),
                LodColumn.pack(Blocks.OAK_LOG, 138, 141, LodColumn.FLAG_LANDMARK),
                LodColumn.pack(Blocks.OAK_LEAVES, 141, 144, LodColumn.FLAG_LANDMARK),
                LodColumn.pack(Blocks.OAK_PLANKS, 144, 147, LodColumn.FLAG_LANDMARK)));

        long terrain = intervalAt(reduced, 64);
        assertTrue(terrain != 0, "Landmarken dürfen die natürliche Terrainhülle nicht verdrängen");
        assertTrue(LodColumn.terrain(terrain));
        assertEquals(1, LodColumn.minY(terrain));
        assertEquals(138, LodColumn.maxY(terrain));
    }

    @Test
    void seed187KeepsTerrainAtTheObservedMinus5760Minus10022RegressionCell() {
        int wx = -5760, wz = -10022;
        int chunkX = Math.floorDiv(wx, 32), chunkZ = Math.floorDiv(wz, 32);
        AlphaWorldGeneratorV2 generator = new AlphaWorldGeneratorV2(187);
        ChunkDecorator decorator = new ChunkDecorator(generator, List.of(new BiomeTreeFeature()));
        ChunkLodColumns columns = ChunkLodColumns.fromGenerator(generator,
                decorator.decorateForLod(chunkX, chunkZ), chunkX, chunkZ, 1);

        LodColumn column = columns.get(Math.floorMod(wx, 32), Math.floorMod(wz, 32), 2);
        long terrain = intervalAt(column, 64);
        assertTrue(terrain != 0,
                "Die reale Seed-187-Zelle darf bei L0→L1 nicht auf Bedrockhöhe zusammenfallen");
        assertTrue(LodColumn.terrain(terrain));
        assertEquals(138, LodColumn.maxY(terrain));
    }

    @Test
    void exactTreeColumnIsNormalizedBeforeTheFourIntervalLimit() {
        Chunk chunk = new Chunk(0, 0);
        for (int z = 0; z < 2; z++) for (int x = 0; x < 2; x++) {
            chunk.setBlock(x, 0, z, Blocks.BEDROCK);
            for (int y = 1; y < 20; y++) chunk.setBlock(x, y, z, Blocks.STONE);
            for (int y = 20; y < 30; y++) chunk.setBlock(x, y, z, Blocks.DIRT);
            for (int y = 30; y < 63; y++) chunk.setBlock(x, y, z, Blocks.STONE);
            chunk.setBlock(x, 63, z, Blocks.GRASS_BLOCK);
        }
        for (int y = 64; y < 68; y++) chunk.setBlock(0, y, 0, Blocks.OAK_LOG);
        for (int y = 68; y < 71; y++) chunk.setBlock(0, y, 0, Blocks.OAK_LEAVES);

        LodColumn l0 = ChunkLodColumns.fromChunk(chunk, flatGenerator(), 0).get(0, 0, 1);
        LodColumn l1 = ChunkLodColumns.fromChunk(chunk, flatGenerator(), 1).get(0, 0, 2);

        assertTerrainShell(l0, 32, 64);
        assertTerrainShell(l1, 32, 64);
        assertTrue(LodColumn.landmark(l0.interval(l0.size() - 1)));
        assertTrue(LodColumn.landmark(l1.interval(l1.size() - 1)));
    }

    @Test
    void generatedTreeSupportAnchorsOnlyTheTrunkToTheCoarseTerrain() {
        WorldGenerator generator = flatGenerator();
        Feature tree = context -> {
            if (context.sourceMinX() != 0 || context.sourceMinZ() != 0) return;
            context.markLodSupport(0, 80, 0);
            for (int y = 80; y < 84; y++) context.set(0, y, 0, Blocks.OAK_LOG);
            context.setIfAir(0, 84, 0, Blocks.OAK_LEAVES);
        };
        ChunkDecorator decorator = new ChunkDecorator(generator, List.of(tree));

        LodColumn column = ChunkLodColumns.fromGenerator(generator,
                decorator.decorateForLod(0, 0), 0, 0, 1).get(0, 0, 2);

        long support = supportedInterval(column);
        assertTrue(support != 0, "Der reduzierte Stamm muss seine Support-Markierung behalten");
        assertEquals(64, LodColumn.minY(support));
        assertEquals(84, LodColumn.maxY(support));
        assertEquals(Blocks.OAK_LOG, LodColumn.state(support));
    }

    @Test
    void exactChunkTreeSupportFollowsTheReducedTerrainInsteadOfItsOldRootHeight() {
        WorldGenerator generator = new WorldGenerator(321) {
            @Override public int sampleHeight(int x, int z) {
                return x == 0 && z == 0 ? 79 : 63;
            }
            @Override public void generate(Chunk chunk) {}
            @Override public int lodWorldBottomState() { return Blocks.BEDROCK; }
            @Override public long sampleGroundSurface(int x, int z) {
                return LodDataSource.pack(Blocks.STONE, sampleHeight(x, z));
            }
        };
        Chunk chunk = new Chunk(0, 0);
        for (int z = 0; z < 2; z++) for (int x = 0; x < 2; x++) {
            int top = x == 0 && z == 0 ? 80 : 64;
            for (int y = 0; y < top; y++) chunk.setBlock(x, y, z, Blocks.STONE);
        }
        for (int y = 80; y < 84; y++) chunk.setBlock(0, y, 0, Blocks.OAK_LOG);

        LodColumn column = ChunkLodColumns.fromChunk(chunk, generator, 1).get(0, 0, 2);
        long support = supportedInterval(column);
        assertTrue(support != 0);
        assertEquals(64, LodColumn.minY(support));
        assertEquals(84, LodColumn.maxY(support));
    }

    @Test
    void seed187ExactSnapshotsAroundReportedTreeHolesKeepTheirL1Shell() {
        AlphaWorldGeneratorV2 generator = new AlphaWorldGeneratorV2(187);
        ChunkDecorator decorator = new ChunkDecorator(generator, List.of(new BiomeTreeFeature()));
        int[][] reported = {{-7030, -9774}, {-7072, -9848}};
        for (int[] point : reported) {
            int chunkX = Math.floorDiv(point[0], 32), chunkZ = Math.floorDiv(point[1], 32);
            Chunk chunk = new Chunk(chunkX, chunkZ);
            generator.generate(chunk);
            decorator.decorate(chunk);
            ChunkLodColumns columns = ChunkLodColumns.fromChunk(chunk, generator, 1);
            for (int z = 0; z < 32; z += 2) for (int x = 0; x < 32; x += 2) {
                LodColumn column = columns.get(x, z, 2);
                long terrain = intervalAt(column, 32);
                assertTrue(terrain != 0 && LodColumn.terrain(terrain),
                        "Fehlende L1-Terrainhuelle bei Chunk " + chunkX + "," + chunkZ
                                + " Zelle " + x + "," + z);
            }
        }
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
                LodColumn.FLAG_LANDMARK | LodColumn.FLAG_SKY_OPEN | LodColumn.FLAG_SUPPORT, 1024);

        assertEquals(1024, LodColumn.coverage(interval));
        assertEquals(LodColumn.FLAG_LANDMARK | LodColumn.FLAG_SKY_OPEN | LodColumn.FLAG_SUPPORT,
                LodColumn.flags(interval));
        assertTrue(LodColumn.support(interval));
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

    private static void assertTerrainShell(LodColumn column, int sampleY, int expectedTop) {
        long terrain = intervalAt(column, sampleY);
        assertTrue(terrain != 0, "Die natuerliche Terrainhuelle fehlt");
        assertTrue(LodColumn.terrain(terrain));
        int top = 0;
        for (int i = 0; i < column.size(); i++) {
            long interval = column.interval(i);
            if (!LodColumn.landmark(interval)) top = Math.max(top, LodColumn.maxY(interval));
        }
        assertEquals(expectedTop, top);
    }

    private static long intervalAt(LodColumn column, int y) {
        for (int i = 0; i < column.size(); i++) {
            long interval = column.interval(i);
            if (y >= LodColumn.minY(interval) && y < LodColumn.maxY(interval)) return interval;
        }
        return 0;
    }

    private static long supportedInterval(LodColumn column) {
        for (int i = 0; i < column.size(); i++) {
            long interval = column.interval(i);
            if (LodColumn.support(interval)) return interval;
        }
        return 0;
    }
}
