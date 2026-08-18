package de.skyengine.game.world.lod;

import java.util.Locale;

/**
 * Reiner Debug-Akkumulator für den {@link LodMesher}: beantwortet quantitativ, WELCHER Faktor
 * das Greedy-Meshing der LOD-Tops begrenzt und wie sich die emittierten Quads auf Flächentypen
 * verteilen. Wird ausschließlich vom {@link LodQuadCensus} gesetzt — in der laufenden Engine
 * bleibt {@code LodMesher.stats} null, sodass kein Overhead entsteht (jede Zählung ist per
 * {@code if (stats != null)} geguardet). Nicht threadsicher; der Zensus mesht single-threaded und
 * summiert über alle Regionen einer Konfiguration in dieselbe Instanz.
 *
 * <p>Zwei getrennte Messgrößen — bewusst nicht vermischt:
 * <ul>
 *   <li><b>Flächentyp-Zählung</b> (Felder {@code topXxx}/{@code wallXxx}/{@code bottom}):
 *       die real emittierten Quads. Bottoms existieren im Spaltenpfad nur am Weltboden und
 *       unter tatsächlich freistehenden Landmarken.</li>
 *   <li><b>Merge-Grenzen</b> ({@code seam*}): eine ordnungsunabhängige Zählung der Zell-Adjazenzen
 *       im Terrain-Top-Raster nach der URSACHE, warum zwei benachbarte Zellen NICHT in dasselbe
 *       Quad gemergt werden dürfen. Jede blockierte Adjazenz ≈ eine potenziell einsparbare
 *       Quad-Kante. Das ist die eigentliche Antwort auf „welcher Faktor begrenzt".</li>
 * </ul>
 *
 * <p><b>Licht:</b> Freie LOD-Oberflächen tragen Skylight 15; Meeresboden-Zellen werden anhand
 * ihrer Wassertiefe abgedunkelt. Unterschiedliche Tiefen können deshalb einen Merge brechen.
 */
public final class LodMeshStats {

    /* --- Real emittierte Quads nach Flächentyp (aus emitTop/emitWall) --- */
    /** Terrain-Tops (face 0, opak) — Boden-/Meeresboden-Oberkanten. */
    public long topTerrain;
    /** Wasser-Tops (face 0, transluzent) — Quellhöhen-Flächen. */
    public long topWater;
    /** Opake Terrain-Basiswände (faces 2–5). */
    public long wallTerrain;
    /** Transluzente Wasser-Wände (Seeufer-/Wasserfall-Kanten). */
    public long wallWater;
    /** Sichtbare Unterseiten am Weltboden oder unter freistehenden Landmarken. */
    public long bottom;
    /** Auflösungssegmente, bei denen mindestens ein äußeres Terrainprofil fehlte. */
    public long transitionProfilesMissing;
    /** Lokal begrenzte Deckel, die eine unerwartet fehlende Generator-Spalte schließen. */
    public long transitionSafetyCaps;
    /** Validierte, fein unterteilte Aufloesungssegmente und ihr groesster Hoehensprung. */
    public long transitionSegments;
    public int transitionMaxDelta;
    public int transitionMaxX, transitionMaxZ, transitionMaxFace;
    public int transitionMaxCoarseLevel, transitionMaxFineLevel;
    public int transitionMaxCoarseSize, transitionMaxFineSize;
    public int transitionMaxCoarseTop, transitionMaxFineTop;

    /* --- Merge-Grenzen im Terrain-Top-Raster (Adjazenzen nach Ursache) ---
       Gezählt wird jede interne +x-/+z-Nachbarschaft der Regionszellen [0,n)² GENAU EINMAL.
       Priorität der Ursachen bei Mehrfach-Unterschied: Material > Höhe > Licht > AO (der „härtere"
       Grund gewinnt; dokumentiert, damit die Summen eindeutig sind). */
    /** Nachbarblock verschieden (Textur-/Materialnaht). */
    public long seamMaterial;
    /** Gleicher Block, andere Boden-Höhe (Reliefstufe — Quelle der meisten Wände). */
    public long seamHeight;
    /** Gleicher Block und gleiche Höhe, aber andere Wasser-Tiefenabdunklung. */
    public long seamLight;
    /** Gleicher Block UND gleiche Höhe, aber AO-Ecken nicht kompatibel (nur bei AO an). */
    public long seamAo;
    /** Nachbar zeigt echtes Terrain (16-Bit-Maske) — extern, kein Greedy-Limiter im engeren Sinn. */
    public long seamClipped;
    /** Adjazenz OHNE Grenze: beide Zellen dürften ins selbe Quad (Merge-Potenzial). */
    public long seamMergeable;

    /* --- Wand-/Skirt-Klassifikation der opaken Terrain-Wände (aus wallsAlongX/Z) ---
       Summe realStep+edgeSkirt+maskSkirt == wallTerrain. */
    /** Wand an echter Reliefstufe (Nachbar niedriger sichtbar). */
    public long wallRealStep;
    /** Wand existiert NUR wegen Regionsrand-Skirt (nTop >= top, ohne Skirt keine Wand). */
    public long wallEdgeSkirt;
    /** Wand an Masken-Kante (geclippter Nachbar) mit fester MASK_EDGE_SKIRT-Tiefe. */
    public long wallMaskSkirt;

    /** Alle Zähler auf 0 — vor jedem Konfigurations-Lauf. */
    public void reset() {
        this.topTerrain = this.topWater = 0;
        this.wallTerrain = this.wallWater = this.bottom = 0;
        this.transitionProfilesMissing = this.transitionSafetyCaps = 0;
        this.transitionSegments = 0;
        this.transitionMaxDelta = 0;
        this.transitionMaxX = this.transitionMaxZ = this.transitionMaxFace = 0;
        this.transitionMaxCoarseLevel = this.transitionMaxFineLevel = 0;
        this.transitionMaxCoarseSize = this.transitionMaxFineSize = 0;
        this.transitionMaxCoarseTop = this.transitionMaxFineTop = 0;
        this.seamMaterial = this.seamHeight = this.seamLight = this.seamAo = 0;
        this.seamClipped = this.seamMergeable = 0;
        this.wallRealStep = this.wallEdgeSkirt = this.wallMaskSkirt = 0;
    }

    /** Druckt den vollständigen Report für eine (config, AO)-Kombination. */
    public void printReport(LodConfig config, boolean ao) {
        long tops = this.topTerrain + this.topWater;
        long walls = this.wallTerrain + this.wallWater;
        long total = tops + walls + this.bottom;

        System.out.printf(Locale.ROOT,
                "%n--- Quad-Statistik rd=%d lodMax=%d AO=%s ---%n",
                config.renderDistance(), config.lodMaxDistance(), ao ? "an" : "aus");

        /* 1. Flächentyp-Split (real emittierte Quads) */
        System.out.println("Flächentypen (real emittierte Quads):");
        printLine("  Top  Terrain (opak)", this.topTerrain, total);
        printLine("  Top  Wasser (transl.)", this.topWater, total);
        printLine("  Seite Terrain-Basis", this.wallTerrain, total);
        printLine("  Seite Wasser (transl.)", this.wallWater, total);
        printLine("  Bottom", this.bottom, total);
        System.out.printf(Locale.ROOT, "  %-24s %12d%n", "= Gesamt", total);

        /* 2. Merge-Grenzen: welcher Faktor blockiert die Top-Zusammenfassung? */
        long seams = this.seamMaterial + this.seamHeight + this.seamLight
                + this.seamAo + this.seamClipped;
        long adjacencies = seams + this.seamMergeable;
        System.out.println("Merge-Grenzen Terrain-Tops (blockierte Zell-Adjazenzen nach Ursache):");
        printLine("  Höhe (Reliefstufe)", this.seamHeight, seams);
        printLine("  Material (Blocknaht)", this.seamMaterial, seams);
        printLine("  Licht (Wassertiefe)", this.seamLight, seams);
        printLine("  AO (Eckwerte)", this.seamAo, seams);
        printLine("  Clip (Chunk-Maske)", this.seamClipped, seams);
        System.out.printf(Locale.ROOT, "  %-24s %12d (%.1f%% aller %d Adjazenzen)%n",
                "= blockierte Kanten", seams,
                adjacencies == 0 ? 0.0 : 100.0 * seams / adjacencies, adjacencies);
        System.out.printf(Locale.ROOT, "  %-24s %12d%n", "  mergebar (frei)", this.seamMergeable);
        System.out.printf(Locale.ROOT, "LOD-Übergänge: fehlende Profile=%d, Sicherheitsdeckel=%d%n",
                this.transitionProfilesMissing, this.transitionSafetyCaps);
        System.out.printf(Locale.ROOT,
                "  Segmente=%d, max Delta=%d bei (%d,%d), Face=%d, L%d/%d, Size=%d/%d, Top=%d/%d%n",
                this.transitionSegments, this.transitionMaxDelta,
                this.transitionMaxX, this.transitionMaxZ, this.transitionMaxFace,
                this.transitionMaxCoarseLevel, this.transitionMaxFineLevel,
                this.transitionMaxCoarseSize, this.transitionMaxFineSize,
                this.transitionMaxCoarseTop, this.transitionMaxFineTop);
        /* 3. Wände/Skirts */
        System.out.println("Terrain-Wände nach Grund:");
        printLine("  echte Reliefstufe", this.wallRealStep, this.wallTerrain);
        printLine("  Regionsrand-Skirt", this.wallEdgeSkirt, this.wallTerrain);
        printLine("  Masken-Kanten-Skirt", this.wallMaskSkirt, this.wallTerrain);
    }

    void recordTransition(int worldX, int worldZ, int face,
                          int coarseLevel, int fineLevel, int coarseSize, int fineSize,
                          int coarseTop, int fineTop) {
        this.transitionSegments++;
        int delta = Math.abs(coarseTop - fineTop);
        if (delta <= this.transitionMaxDelta) return;
        this.transitionMaxDelta = delta;
        this.transitionMaxX = worldX;
        this.transitionMaxZ = worldZ;
        this.transitionMaxFace = face;
        this.transitionMaxCoarseLevel = coarseLevel;
        this.transitionMaxFineLevel = fineLevel;
        this.transitionMaxCoarseSize = coarseSize;
        this.transitionMaxFineSize = fineSize;
        this.transitionMaxCoarseTop = coarseTop;
        this.transitionMaxFineTop = fineTop;
    }

    private static void printLine(String label, long value, long total) {
        double pct = total == 0 ? 0.0 : 100.0 * value / total;
        System.out.printf(Locale.ROOT, "  %-24s %12d (%5.1f%%)%n", label, value, pct);
    }
}
