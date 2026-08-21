package de.skyengine.game.world.lod;

import de.skyengine.core.file.Files;
import de.skyengine.core.settings.GameSettings;
import de.skyengine.game.world.block.Blocks;
import de.skyengine.game.world.chunk.Chunk;
import de.skyengine.game.world.chunk.ChunkMesher;
import de.skyengine.game.world.chunk.ChunkSection;
import de.skyengine.game.world.generator.WorldGenerator;
import de.skyengine.game.world.generator.feature.ChunkDecorator;
import de.skyengine.game.world.generator.feature.trees.BiomeTreeFeature;
import de.skyengine.game.world.generator.generators.AlphaWorldGeneratorV2;
import de.skyengine.game.world.lod.LodManager.LodMeshResult;

import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Standalone-Zensus (eigene main, kein GL/Engine-Start — Gegenstück zum GeneratorMapExporter
 * für die LOD-Schicht): mesht den kompletten LOD-Ring einer festen Konfiguration und summiert
 * Quads/Vertices pro Level. Fester Seed und fester Anker machen die Zahlen deterministisch —
 * damit lassen sich Merge-Änderungen im {@link LodMesher} exakt vorher/nachher vergleichen
 * (In-Engine-Läufe streuen, weil die Spawn-Position pro Lauf variiert).
 *
 * <p>Gemessen wird der SPALTENPFAD ({@code hasColumns() == true}) — derselbe, den die Engine
 * über {@code PersistentLodDataSource} fährt. Der frühere Lauf über
 * {@link GeneratorLodDataSource} traf den Heightmap-Fallback und war damit für
 * Merge-Änderungen im Spaltenpfad blind.
 *
 * <p>Ring-Geometrie exakt wie {@code LodManager.recomputeDesired}: Anker im Zentrum der
 * Region (0,0), Regionen zählen über {@code d - HALF_DIAG < outer}, Level über
 * {@link LodConfig#levelAt}. Maske überall 0 (kein Chunk-Clipping) — leichter Überschätzer
 * gegenüber der Engine nahe des Spielers, aber in Vorher- und Nachher-Lauf identisch.
 */
public final class LodQuadCensus {

    private static final int SEED = 123; // gleicher Seed wie World

    /* Anker = Zentrum der Region (0,0) — Regionszentren-Abstände wie in recomputeDesired */
    private static final int ANCHOR = LodMesher.REGION_BLOCKS / 2;

    private static final int QUAD_INTS = 4 * ChunkMesher.VERTEX_SIZE;

    public static void main(String[] args) {
        /* Block-Registry GL-frei laden/backen (Layer-Indizes entstehen beim Bake, kein GL) */
        Blocks.bootstrap(new File(Files.RESOURCES_PATH, "game/blocks"));

        WorldGenerator generator = new AlphaWorldGeneratorV2(SEED);
        LodBlockAppearance appearance = new LodBlockAppearance();

        /* AO=an (realistische Einstellung) fest. */
        GameSettings.get().ambientOcclusion = true;
        census(generator, appearance, LodConfig.of(16, 128), true);
    }

    /** Mesht alle Regionen des Rings und druckt die Quad-/Vertex-Summen pro Level. */
    private static void census(WorldGenerator generator, LodBlockAppearance appearance,
                               LodConfig config, boolean ao) {
        LodMesher mesher = new LodMesher();
        LodMeshStats stats = new LodMeshStats();
        mesher.setStats(stats); // aktiviert die Quad-Statistik (in-engine null → aus)
        double outer = config.outerRadiusBlocks();
        int radius = (int) Math.ceil((outer + LodMesher.HALF_DIAG) / LodMesher.REGION_BLOCKS);

        int levels = config.maxEffectiveLevel() + 1;
        long[] regions = new long[levels];
        long[] opaque = new long[levels];
        long[] translucent = new long[levels];
        long[] cells = new long[levels];

        long start = System.currentTimeMillis();
        /* Level für Level, mit geleertem Cache dazwischen: sonst läge der komplette Ring in
           allen Auflösungen gleichzeitig im Speicher. */
        ColumnCensusSource source = new ColumnCensusSource(generator);
        for (int level = 1; level < levels; level++) {
            source.clear();
            int cellsPerRow = LodMesher.REGION_BLOCKS / config.cellSize(level);
            for (int rz = -radius; rz <= radius; rz++) {
                for (int rx = -radius; rx <= radius; rx++) {
                    double cx = (rx + 0.5) * LodMesher.REGION_BLOCKS - ANCHOR;
                    double cz = (rz + 0.5) * LodMesher.REGION_BLOCKS - ANCHOR;
                    double d = Math.sqrt(cx * cx + cz * cz);
                    if (d - LodMesher.HALF_DIAG >= outer) continue;
                    if (config.levelAt(d) != level) continue;

                    LodMeshResult result = mesher.mesh(source, appearance, config, level, 1,
                            rx, rz, 0, 0, ANCHOR, ANCHOR);
                    regions[level]++;
                    cells[level] += (long) cellsPerRow * cellsPerRow;
                    opaque[level] += result.opaqueData().length / QUAD_INTS;
                    translucent[level] += result.translucentData().length / QUAD_INTS;
                }
            }
        }
        long elapsed = System.currentTimeMillis() - start;

        System.out.printf(Locale.ROOT, "%n=== Zensus rd=%d lodMax=%d AO=%s (Seed %d, %d ms) ===%n",
                config.renderDistance(), config.lodMaxDistance(), ao ? "an" : "aus", SEED, elapsed);
        System.out.printf(Locale.ROOT, "%-6s %10s %14s %14s %14s %14s %10s%n",
                "Level", "Regionen", "OpakQ", "TranslQ", "GesamtQ", "Vertices", "Q/Zelle");
        long totalRegions = 0, totalOpaque = 0, totalTranslucent = 0, totalCells = 0;
        for (int l = 1; l < levels; l++) {
            if (regions[l] == 0) continue;
            long q = opaque[l] + translucent[l];
            System.out.printf(Locale.ROOT, "L%-5d %10d %14d %14d %14d %14d %10.3f%n",
                    l, regions[l], opaque[l], translucent[l], q, q * 4,
                    (double) q / cells[l]);
            totalRegions += regions[l];
            totalOpaque += opaque[l];
            totalTranslucent += translucent[l];
            totalCells += cells[l];
        }
        long totalQ = totalOpaque + totalTranslucent;
        System.out.printf(Locale.ROOT,
                "%-6s %10d %14d %14d %14d %14d %10.3f (%.1f MiB Vertexdaten)%n",
                "Summe", totalRegions, totalOpaque, totalTranslucent, totalQ, totalQ * 4,
                totalCells == 0 ? 0 : (double) totalQ / totalCells,
                totalQ * 4.0 * ChunkMesher.VERTEX_SIZE * Integer.BYTES / (1024.0 * 1024.0));

        /* Detailreport: Flächentypen, Merge-Grenzen nach Ursache, Skirt-Anteil. */
        stats.printReport(config, ao);
    }

    /**
     * Spaltenquelle des Zensus: erzeugt {@link ChunkLodColumns} auf Anforderung und hält sie
     * für die Dauer EINES Levels. Liefert bewusst {@code hasColumns() == true}, damit der
     * Mesher denselben {@code meshColumns}-Pfad nimmt wie die Engine.
     */
    private static final class ColumnCensusSource implements LodDataSource {
        private final WorldGenerator generator;
        private final ChunkDecorator decorator;
        private final Map<Long, ChunkLodColumns> chunks = new HashMap<>();

        private ColumnCensusSource(WorldGenerator generator) {
            this.generator = generator;
            this.decorator = new ChunkDecorator(generator, List.of(new BiomeTreeFeature()));
        }

        @Override public boolean hasColumns() { return true; }

        /**
         * Der Cache ist nach (Chunk, Level) geschlüsselt: {@code replaceRegionHalos} sampelt am
         * Regionsrand die Spalten des NACHBAR-Levels, ein Cache je Level würde dort werfen.
         */
        @Override
        public LodColumn sampleColumn(int x, int z, int size) {
            int level = Integer.numberOfTrailingZeros(size);
            int cx = x >> ChunkSection.SHIFT, cz = z >> ChunkSection.SHIFT;
            long key = Chunk.key(cx, cz) ^ ((long) level << 56);
            ChunkLodColumns columns = this.chunks.computeIfAbsent(key, ignored ->
                    ChunkLodColumns.fromGenerator(this.generator,
                            this.decorator.decorateForLod(cx, cz), cx, cz, level));
            return columns.get(x & ChunkSection.MASK, z & ChunkSection.MASK, size);
        }

        void clear() {
            this.chunks.clear();
        }

        @Override
        public long sampleSurface(int x, int z, int size) {
            LodColumn column = this.sampleColumn(x, z, size);
            if (column.size() == 0) return LodDataSource.pack(Blocks.AIR, 0);
            long top = column.interval(column.size() - 1);
            return LodDataSource.pack(LodColumn.state(top), LodColumn.maxY(top) - 1);
        }

        @Override public int grassTintAt(int x, int z) { return this.generator.grassTintAt(x, z); }
        @Override public int foliageTintAt(int x, int z) { return this.generator.foliageTintAt(x, z); }
    }

    private LodQuadCensus() {}
}
