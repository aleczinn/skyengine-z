package de.skyengine.game.world.lod;

import de.skyengine.core.file.Files;
import de.skyengine.core.settings.GameSettings;
import de.skyengine.game.world.block.Blocks;
import de.skyengine.game.world.chunk.ChunkMesher;
import de.skyengine.game.world.generator.generators.AlphaWorldGeneratorV2;
import de.skyengine.game.world.lod.LodManager.LodMeshResult;

import java.io.File;
import java.util.Locale;

/**
 * Standalone-Zensus (eigene main, kein GL/Engine-Start — Gegenstück zum GeneratorMapExporter
 * für die LOD-Schicht): mesht den kompletten LOD-Ring fester Konfigurationen über die pure
 * {@link GeneratorLodDataSource} und summiert Quads/Vertices pro Level. Fester Seed und
 * fester Anker machen die Zahlen deterministisch — damit lassen sich Merge-Änderungen im
 * {@link LodMesher} exakt vorher/nachher vergleichen (In-Engine-Läufe streuen, weil die
 * Spawn-Position pro Lauf variiert).
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

        GeneratorLodDataSource source = new GeneratorLodDataSource(new AlphaWorldGeneratorV2(SEED));
        LodBlockAppearance appearance = new LodBlockAppearance();

        /* AO=an (realistische Einstellung) fest. */
        GameSettings.get().ambientOcclusion = true;
        census(source, appearance, LodConfig.of(16, 128), true);
        census(source, appearance, LodConfig.of(16, 512), true);
    }

    /** Mesht alle Regionen des Rings und druckt die Quad-/Vertex-Summen pro Level. */
    private static void census(GeneratorLodDataSource source, LodBlockAppearance appearance,
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

        long start = System.currentTimeMillis();
        for (int rz = -radius; rz <= radius; rz++) {
            for (int rx = -radius; rx <= radius; rx++) {
                double cx = (rx + 0.5) * LodMesher.REGION_BLOCKS - ANCHOR;
                double cz = (rz + 0.5) * LodMesher.REGION_BLOCKS - ANCHOR;
                double d = Math.sqrt(cx * cx + cz * cz);
                if (d - LodMesher.HALF_DIAG >= outer) continue;

                int level = config.levelAt(d);
                LodMeshResult result = mesher.mesh(source, appearance, config, level, 1,
                        rx, rz, 0, 0, ANCHOR, ANCHOR);
                regions[level]++;
                opaque[level] += result.opaqueData().length / QUAD_INTS;
                translucent[level] += result.translucentData().length / QUAD_INTS;
            }
        }
        long elapsed = System.currentTimeMillis() - start;

        System.out.printf(Locale.ROOT, "%n=== Zensus rd=%d lodMax=%d AO=%s (Seed %d, %d ms) ===%n",
                config.renderDistance(), config.lodMaxDistance(), ao ? "an" : "aus", SEED, elapsed);
        System.out.printf(Locale.ROOT, "%-6s %10s %14s %14s %14s %14s%n",
                "Level", "Regionen", "OpakQ", "TranslQ", "GesamtQ", "Vertices");
        long totalRegions = 0, totalOpaque = 0, totalTranslucent = 0;
        for (int l = 1; l < levels; l++) {
            if (regions[l] == 0) continue;
            long q = opaque[l] + translucent[l];
            System.out.printf(Locale.ROOT, "L%-5d %10d %14d %14d %14d %14d%n",
                    l, regions[l], opaque[l], translucent[l], q, q * 4);
            totalRegions += regions[l];
            totalOpaque += opaque[l];
            totalTranslucent += translucent[l];
        }
        long totalQ = totalOpaque + totalTranslucent;
        System.out.printf(Locale.ROOT, "%-6s %10d %14d %14d %14d %14d (%.1f MiB Vertexdaten)%n",
                "Summe", totalRegions, totalOpaque, totalTranslucent, totalQ, totalQ * 4,
                totalQ * 4.0 * ChunkMesher.VERTEX_SIZE * Integer.BYTES / (1024.0 * 1024.0));

        /* Detailreport: Flächentypen, Merge-Grenzen nach Ursache, Skirt-Anteil. */
        stats.printReport(config, ao);
    }

    private LodQuadCensus() {}
}
