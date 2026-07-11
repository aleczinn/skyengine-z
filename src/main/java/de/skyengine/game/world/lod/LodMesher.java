package de.skyengine.game.world.lod;

import de.skyengine.core.settings.GameSettings;
import de.skyengine.game.world.block.model.BakedQuad;
import de.skyengine.game.world.block.model.BlockModels;
import de.skyengine.game.world.chunk.ChunkMesher;
import de.skyengine.game.world.chunk.FluidGeometry;
import de.skyengine.game.world.lod.LodManager.LodMeshResult;

import java.util.Arrays;

/**
 * Mesht LOD-Regionen <b>blockbasiert</b> (Voxel-Optik wie echtes Terrain) in zwei Schichten:
 * <ul>
 *   <li><b>Terrain (opak):</b> pro Zelle (Stride 2^Level Blöcke, global ausgerichtetes
 *       Raster) ein flaches Top-Quad auf der BODEN-Oberkante (unter Wasser der Meeres-/
 *       Seeboden) plus senkrechte Wände zu niedrigeren Nachbarzellen; Ecken-AO als
 *       Hillshading aus den Boden-Höhen.</li>
 *   <li><b>Wasser (transluzent):</b> über Fluid-Zellen ein Top-Quad auf der Quellhöhe
 *       ({@link FluidGeometry#SOURCE_HEIGHT}) plus Wände nur an Wasserkörper-Rändern —
 *       der Boden darunter bleibt durch das Wasser sichtbar.</li>
 * </ul>
 * Helligkeit über {@link BlockModels#FACE_BRIGHTNESS}; Texturen/Tints aus der
 * {@link LodBlockAppearance}.
 *
 * <p><b>Determinismus:</b> Jede Zelle wird rein am Zellmittel gesampelt — identisch aus Sicht
 * aller Regionen (keine Grenzfall-Sonderpfade). An Regionsrand-Kanten wird IMMER eine Wand
 * mit tiefem Skirt emittiert (ein einheitlicher Randfall) — sie verdeckt Level-Wechsel und
 * Remesh-Latenz benachbarter Regionen gleichermaßen.
 *
 * <p><b>Clipping:</b> übersprungen werden genau die Zellen, deren Chunk laut 16-Bit-Maske
 * des Jobs gerade echtes Terrain zeigt — LOD ersetzt Chunks exakt dort, wo keine sind.
 *
 * <p><b>yBase:</b> Vertices werden relativ zu einer Regionsbasis gepackt (u16 trägt nur
 * ~254 Blöcke Spanne, Gipfel gehen höher); der Renderer schiebt per Draw-Offset zurück.
 *
 * <p>Läuft auf den Chunk-Workern, liest ausschließlich die {@link LodDataSource}. Ausgabe:
 * gepacktes 16-Byte-Vertex-Format des {@link ChunkMesher}. Eine Instanz pro Worker-Thread.
 * Gegen Quad-Explosion: 1D-Greedy-Merge (Tops/N-S-Wände entlang x, W-O-Wände entlang z),
 * Deckel {@link #MAX_MERGE_BLOCKS}.
 */
public final class LodMesher {

    /** Kantenlänge einer LOD-Region in Blöcken (4x4 Chunks, fix über alle Level). */
    public static final int REGION_BLOCKS = 128;

    /** Halbe Diagonale einer Region — Toleranz für Kreis-Überlappungstests. */
    public static final float HALF_DIAG = 90.6F;

    /* Merge-/UV-Deckel in Blöcken (UV-Fixed-Point 6.10 trägt max ~63; 32 lässt Reserve). */
    private static final int MAX_MERGE_BLOCKS = 32;

    /* Rand-Skirt: BASE·2^Level, gedeckelt. Herleitung MAX: Y-Feld = u16, max y_rel ≈ 254,99;
       nutzbare Spanne nach Bias + yBase-Marge ≈ 253 = Relief + Skirt + 3. Bei Relief_max ≈ 200
       pro Region (Mountain-Ridged) bleibt Skirt ≤ 50 → 48 (deckt auch Stride-16-Übergänge an
       steilen Hängen, ~40 Blöcke). */
    private static final int BASE_SKIRT = 16;
    private static final int MAX_SKIRT = 48;

    /* Sicherheitsmarge an Masken-Kanten (nClipped, kein Regionsrand): deckt nur die
       Sample-Ungenauigkeit/Remesh-Latenz ab, bewusst konstant statt level-/differenzabhängig -
       der reale Höhenunterschied steckt bereits in min(nTop, top). Deutlich kleiner als jeder
       edgeSkirtOf(level) (>=16), daher kein neues Überlauf-Risiko in yBase. */
    private static final float MASK_EDGE_SKIRT = 3F;

    /* Face-Indizes wie BlockModels: 0=top, 2=north(-z), 3=south(+z), 4=west(-x), 5=east(+x) */

    private static final int QUAD_INTS = 4 * ChunkMesher.VERTEX_SIZE;

    /* Neutral-AO für Wasser-Tops (eben, kein Hillshading) — nur lesend verwendet. */
    private static final float[] AO_NONE = {1F, 1F, 1F, 1F};

    /* --- Wiederverwendete Puffer (eine Instanz pro Worker-Thread) --- */
    private long[] cells = new long[0];        // (n+2)² Oberflächen-Samples inkl. Randring
    /* Boden-Samples (ohne Wasser): für Nicht-Fluid-Zellen identisch mit cells; für
       Fluid-Zellen der feste Boden darunter — Basis für Terrain-Tops/-Wände/AO/yBase. */
    private long[] ground = new long[0];
    private boolean[] clipped = new boolean[0];
    /* Merge-Marker der Wasser-Tops (2D-Greedy): true = Zelle schon in ein Quad gemerged. */
    private boolean[] waterConsumed = new boolean[0];
    /* Getrennte Ausgabepuffer: Fluid-Top-Quads -> translucent (transparent, eigene Arena/
       eigener Draw-Call im Renderer), alles andere (Terrain-Tops + ALLE Wände/Skirts,
       auch an Fluid-Zellen) -> opaque. Wände bleiben bewusst immer opak — sie stellen feste
       Geometrie unter/neben der Wasseroberfläche dar, keine Sichtachse durch Wasser. */
    private int[] outOpaque = new int[16384];
    private int viOpaque;
    private int[] outTranslucent = new int[1024];
    private int viTranslucent;
    private int stride, cellCount;             // Kontext des laufenden mesh()-Aufrufs
    private int yBase, edgeSkirt;
    private int sizeRegions;                   // Footprint in 128er-Regionen (1 oder 4)
    private float posScale;                    // Vertex-Packungs-Skala (s. posScaleFor)
    private LodBlockAppearance appearance;
    private LodDataSource source;              // fuer Biome-Tint-Samples an Quad-Zentren
    private int regionBaseX, regionBaseZ;      // Weltkoordinaten-Ursprung der Region
    private float minBottom, maxTop;           // absolut (fürs Frustum-AABB)
    private final float[] aoScratch = new float[4]; // wiederverwendet: P1,P2,P3,P4 pro Top-Quad

    /** Skirt-Tiefe an Regionsrand-Kanten, wächst mit der Zellgröße (s. MAX_SKIRT-Herleitung). */
    private static int edgeSkirtOf(int level) {
        return Math.min(BASE_SKIRT << level, MAX_SKIRT);
    }

    /* Empirisch (gemessen bei RD=16/lodMax=128: ohne Overlay-Wände 1,56, MIT koplanaren
       Gras-Overlay-Wänden 1,97 Quads je LOD-Zelle — Boden-Top + Wände zu niedrigeren Nachbarn).
       Der Aufschlag auf 2,25 (~+14 %) deckt wand-reiches (bergiges) Terrain und die erste
       Füllung ohne Grow ab. */
    private static final float QUADS_PER_CELL = 2.25F;

    /**
     * Schätzt die für die LOD-OPAQUE-Arena nötige Bytemenge aus der Ring-Konfiguration, damit
     * der {@link de.skyengine.graphics.world.ChunkRenderer} die Arena gleich groß genug anlegt
     * (kein Treppen-Wachstum beim Start → weniger NVIDIA-0x20072-Warnungen; die Arena wächst bei
     * Bedarf trotzdem weiter). Iteriert das Regionsraster im Außenradius und summiert die Zellen
     * je Region ((REGION_BLOCKS/2^level)²) über dieselbe pure {@link LodConfig#levelAt}-Formel wie
     * der Mesher. Skaliert damit automatisch mit renderDistance/lodMaxDistance. Reine Schätzung.
     */
    public static long estimateOpaqueArenaBytes(LodConfig config) {
        double outer = config.outerRadiusBlocks();
        /* Exakt dieselbe Geometrie wie LodManager.recomputeDesired: der Anker liegt im
           Regionszentrum, Abstände der Regionszentren sind also Vielfache von REGION_BLOCKS;
           der Rand-Ring zählt über d - HALF_DIAG < outer mit (Kreis-Überlappung). */
        int rr = (int) Math.ceil((outer + HALF_DIAG) / REGION_BLOCKS);
        long cells = 0;
        for (int rz = -rr; rz <= rr; rz++) {
            for (int rx = -rr; rx <= rr; rx++) {
                double dist = Math.sqrt((double) (rx * rx + rz * rz)) * REGION_BLOCKS;
                if (dist - HALF_DIAG >= outer) continue;
                int cellsPerRow = REGION_BLOCKS / config.cellSize(config.levelAt(dist));
                cells += (long) cellsPerRow * cellsPerRow;
            }
        }
        long quads = (long) (cells * QUADS_PER_CELL);
        return quads * QUAD_INTS * Integer.BYTES;
    }

    /**
     * Mesht eine Region. Worker-Thread, reine Daten, kein GL.
     *
     * @param mask   16-Bit-Maske der 4×4 Chunks: gesetzt = Chunk zeigt echtes Terrain → clippen
     * @param ax,az  Anker (Blockkoordinaten des Spieler-Regionszentrums der Desired-Epoche) —
     *               Basis der Level-Zuordnung, muss zum LodManager passen (pure Funktion)
     */
    public LodMeshResult mesh(LodDataSource source, LodBlockAppearance appearance, LodConfig config,
                              int level, int sizeRegions, int rx, int rz, int epoch, int mask, int ax, int az) {
        int s = config.cellSize(level);
        int regionBlocks = sizeRegions * REGION_BLOCKS;
        int n = regionBlocks / s;
        this.stride = n + 2;                    // Zellen -1..n (Randring für Wände)
        this.cellCount = n;
        this.sizeRegions = sizeRegions;
        this.appearance = appearance;
        this.source = source;
        this.edgeSkirt = edgeSkirtOf(level);
        /* Positions-Skala der Vertex-Packung — muss zum per-Draw .w des Renderers passen
           (Superregionen packen mit 1/64, s. ChunkRenderer-Shader und posScaleFor). */
        this.posScale = posScaleFor(sizeRegions);
        int baseX = rx * REGION_BLOCKS;
        int baseZ = rz * REGION_BLOCKS;
        this.regionBaseX = baseX;
        this.regionBaseZ = baseZ;

        /* Komplett von echtem Terrain bedeckt → nichts zu meshen (spart das Sampling). */
        if (mask == 0xFFFF) {
            return new LodMeshResult(level, rx, rz, sizeRegions, epoch, mask, 0, new int[0], new int[0], 0F, 0F);
        }

        if (this.cells.length < this.stride * this.stride) {
            this.cells = new long[this.stride * this.stride];
            this.ground = new long[this.stride * this.stride];
        }
        if (this.clipped.length < n * n) this.clipped = new boolean[n * n];
        this.viOpaque = 0;
        this.viTranslucent = 0;
        this.minBottom = Float.MAX_VALUE;
        this.maxTop = -Float.MAX_VALUE;

        /* 1. Zellen sampeln (inkl. Randring), rein am Zellmittel — deterministisch identisch
           aus Sicht aller Regionen. Zellen fremder Regionen auf DEREN Zellraster (gleiche
           pure levelAt-Zuordnung wie im LodManager). Fluid-Zellen bekommen zusätzlich das
           Boden-Sample (fester Grund unter dem Wasser); minHeight/yBase folgen dem Boden. */
        int minHeight = Integer.MAX_VALUE;
        for (int cz = -1; cz <= n; cz++) {
            for (int cx = -1; cx <= n; cx++) {
                int wx = baseX + cx * s, wz = baseZ + cz * s;
                long sample = sampleCell(source, config, wx, wz, s, rx, rz, sizeRegions, ax, az);
                long groundSample = this.appearance.isFluid(LodDataSource.block(sample))
                        ? sampleGroundCell(source, config, wx, wz, s, rx, rz, sizeRegions, ax, az) : sample;
                int i = (cz + 1) * this.stride + (cx + 1);
                this.cells[i] = sample;
                this.ground[i] = groundSample;
                int h = LodDataSource.height(groundSample);
                if (h < minHeight) minHeight = h;
            }
        }

        /* yBase: u16 trägt nur ~254 Blöcke Spanne — relativ zur tiefsten Geometrie packen. */
        this.yBase = Math.max(0, minHeight - this.edgeSkirt - 2);

        /* 2. Clip-Maske pro Zelle (Zellen liegen raster-aligned in genau einem Chunk).
           mask == 0 (u.a. IMMER bei Superregionen, Distanz-Gate) überspringt die Expansion —
           schneller, und die 16-Bit-Bit-Arithmetik (Stride *4) gilt ohnehin nur für n <= 32. */
        if (mask == 0) {
            java.util.Arrays.fill(this.clipped, 0, n * n, false);
        } else {
            int cellsPerChunk = 32 / s;
            for (int cz = 0; cz < n; cz++) {
                for (int cx = 0; cx < n; cx++) {
                    int bit = (cz / cellsPerChunk) * 4 + (cx / cellsPerChunk);
                    this.clipped[cz * n + cx] = (mask & (1 << bit)) != 0;
                }
            }
        }

        /* 3a. Terrain-Tops über die Boden-Samples (auch unter Wasser — der Meeresboden ist
           durch das transluzente Wasser sichtbar). Merge nur bei uniformem UND gleichem AO —
           wie im ChunkMesher-Greedy: uneinheitliche Zellen einzeln mit per-Ecke-AO, sonst
           interpoliert die GPU die Eckwerte als lange Gradient-Bänder über den Run. */
        /* AO aus (Setting): neutral hell (1.0) + frei mergen nach Block+Höhe — exakt wie im
           ChunkMesher (aoIdx 3 = 1.0), damit der Look über die LOD-Grenze konsistent bleibt.
           Live gelesen; der LodManager bumpt bei AO-Toggle die Epoche → alle Regionen neu. */
        boolean useAo = GameSettings.get().ambientOcclusion;
        int maxRun = Math.max(1, MAX_MERGE_BLOCKS / s);
        for (int cz = 0; cz < n; cz++) {
            int cx = 0;
            while (cx < n) {
                if (this.clipped[cz * n + cx]) {
                    cx++;
                    continue;
                }
                long g = this.groundCell(cx, cz);
                float top = this.topOf(g);
                float[] ao;
                boolean uniform;
                if (useAo) {
                    ao = this.aoScratch;
                    this.computeCellAo(cx, cz, top, ao);
                    uniform = ao[0] == ao[1] && ao[1] == ao[2] && ao[2] == ao[3];
                } else {
                    ao = AO_NONE;
                    uniform = true;
                }

                int run = 1;
                if (uniform) {
                    while (cx + run < n && run < maxRun && !this.clipped[cz * n + cx + run]
                            && this.groundCell(cx + run, cz) == g
                            && (!useAo || this.cellAoUniform(cx + run, cz, top, ao[0]))) run++;
                }
                this.emitTop(LodDataSource.block(g), ao, cx * s, cz * s, (cx + run) * s, (cz + 1) * s, top);
                cx += run;
            }
        }

        /* 3b. Wasser-Tops über die Oberflächen-Samples (nur Fluid-Zellen), ohne AO —
           Wasserflächen sind eben; AO würde fleckig und zerstört den Merge. 2D-Greedy
           (Breite entlang x, dann Höhe entlang z, wie im ChunkMesher-Greedy) — 1D erzeugte
           lange dünne Streifen; flache Ozeanflächen werden so zu wenigen großen Rechtecken. */
        if (this.waterConsumed.length < n * n) this.waterConsumed = new boolean[n * n];
        else Arrays.fill(this.waterConsumed, 0, n * n, false);
        for (int cz = 0; cz < n; cz++) {
            int cx = 0;
            while (cx < n) {
                int idx = cz * n + cx;
                if (this.clipped[idx] || this.waterConsumed[idx]) {
                    cx++;
                    continue;
                }
                long sample = this.cell(cx, cz);
                int block = LodDataSource.block(sample);
                if (!this.appearance.isFluid(block)) {
                    cx++;
                    continue;
                }
                int w = 1;
                while (cx + w < n && w < maxRun && !this.clipped[cz * n + cx + w]
                        && !this.waterConsumed[cz * n + cx + w] && this.cell(cx + w, cz) == sample) w++;

                int h = 1;
                expand:
                while (cz + h < n && h < maxRun) {
                    for (int i = 0; i < w; i++) {
                        int j = (cz + h) * n + (cx + i);
                        if (this.clipped[j] || this.waterConsumed[j] || this.cell(cx + i, cz + h) != sample) {
                            break expand;
                        }
                    }
                    h++;
                }

                for (int dz = 0; dz < h; dz++) {
                    for (int dx = 0; dx < w; dx++) this.waterConsumed[(cz + dz) * n + (cx + dx)] = true;
                }
                this.emitTop(block, AO_NONE, cx * s, cz * s, (cx + w) * s, (cz + h) * s, this.topOf(sample));
                cx += w;
            }
        }

        /* 4. Terrain-Wände (Boden-Höhen): die höhere Zelle besitzt die Wand; an
           Regionsrand-Kanten IMMER mit Skirt. */
        for (int cz = 0; cz < n; cz++) {
            this.wallsAlongX(cz, -1, 2, cz == 0, s, maxRun);       // north
            this.wallsAlongX(cz, +1, 3, cz == n - 1, s, maxRun);   // south
        }
        for (int cx = 0; cx < n; cx++) {
            this.wallsAlongZ(cx, -1, 4, cx == 0, s, maxRun);       // west
            this.wallsAlongZ(cx, +1, 5, cx == n - 1, s, maxRun);   // east
        }

        /* 5. Wasser-Wände (Wasserspiegel-Höhen): nur wo eine Fluid-Zelle an eine Zelle mit
           niedrigerer sichtbarer Oberkante grenzt (Seeufer am Hang, Wasserfall-Kante) — im
           flachen Ozean entstehen KEINE Wände, auch nicht an Regionsrändern: Wasser ist über
           Levelgrenzen koplanar (SOURCE_HEIGHT konstant), der Skirt-Zweck entfällt. */
        for (int cz = 0; cz < n; cz++) {
            this.waterWallsAlongX(cz, -1, 2, s, maxRun);           // north
            this.waterWallsAlongX(cz, +1, 3, s, maxRun);           // south
        }
        for (int cx = 0; cx < n; cx++) {
            this.waterWallsAlongZ(cx, -1, 4, s, maxRun);           // west
            this.waterWallsAlongZ(cx, +1, 5, s, maxRun);           // east
        }

        int[] opaqueData = this.viOpaque == 0 ? new int[0] : Arrays.copyOf(this.outOpaque, this.viOpaque);
        int[] translucentData = this.viTranslucent == 0 ? new int[0] : Arrays.copyOf(this.outTranslucent, this.viTranslucent);
        boolean empty = this.viOpaque == 0 && this.viTranslucent == 0;
        float minY = empty ? 0F : this.minBottom;
        float maxY = empty ? 0F : this.maxTop;
        this.appearance = null;
        this.source = null;
        return new LodMeshResult(level, rx, rz, this.sizeRegions, epoch, mask, this.yBase, opaqueData, translucentData, minY, maxY);
    }

    /**
     * Positions-Skala der Vertex-Packung je Regionsgröße: 128er packen mit 1/256 (wie
     * Sections), Superregionen mit 1/64 — der u16-Fixed-Point trägt bei 1/256 nur ~255
     * Blöcke Region-lokale Spanne, bei 1/64 ~1023 (x/z UND y). Muss exakt zum per-Draw
     * .w-Wert des Renderers passen ({@code LodMesh.invPosScale}).
     */
    public static float posScaleFor(int sizeRegions) {
        return sizeRegions > 1 ? 64F : ChunkMesher.POS_SCALE;
    }

    /* ------------------------- Sampling ------------------------- */

    /**
     * Sample einer Zelle mit Ursprung (wx,wz): innerhalb des eigenen Footprints
     * ([rx, rx+size) × [rz, rz+size) in 128er-Regionskoordinaten) aufs eigene Raster (s),
     * sonst aufs Raster des Nachbar-Levels ausgerichtet. Innerhalb einer Superregion mit
     * uniformem Level ist das Sample identisch zu dem der ungemergten 128er-Regionen
     * (s teilt 128, Zellursprünge liegen auf demselben globalen Raster) — Determinismus
     * an allen Nähten bleibt erhalten.
     */
    private static long sampleCell(LodDataSource source, LodConfig config, int wx, int wz, int s,
                                   int rx, int rz, int size, int ax, int az) {
        int rxc = Math.floorDiv(wx, REGION_BLOCKS);
        int rzc = Math.floorDiv(wz, REGION_BLOCKS);
        if (rxc >= rx && rxc < rx + size && rzc >= rz && rzc < rz + size) {
            return source.sampleSurface(wx, wz, s);
        }

        int s2 = config.cellSize(neighborLevel(config, rxc, rzc, ax, az));
        return source.sampleSurface(Math.floorDiv(wx, s2) * s2, Math.floorDiv(wz, s2) * s2, s2);
    }

    /** Boden-Variante von {@link #sampleCell} — gleiche Rasterlogik, liefert den festen Grund. */
    private static long sampleGroundCell(LodDataSource source, LodConfig config, int wx, int wz, int s,
                                         int rx, int rz, int size, int ax, int az) {
        int rxc = Math.floorDiv(wx, REGION_BLOCKS);
        int rzc = Math.floorDiv(wz, REGION_BLOCKS);
        if (rxc >= rx && rxc < rx + size && rzc >= rz && rzc < rz + size) {
            return source.sampleGround(wx, wz, s);
        }

        int s2 = config.cellSize(neighborLevel(config, rxc, rzc, ax, az));
        return source.sampleGround(Math.floorDiv(wx, s2) * s2, Math.floorDiv(wz, s2) * s2, s2);
    }

    private static int neighborLevel(LodConfig config, int nrx, int nrz, int ax, int az) {
        double dx = (nrx + 0.5) * REGION_BLOCKS - ax;
        double dz = (nrz + 0.5) * REGION_BLOCKS - az;
        return config.levelAt(Math.sqrt(dx * dx + dz * dz));
    }

    private long cell(int cx, int cz) {
        return this.cells[(cz + 1) * this.stride + (cx + 1)];
    }

    private long groundCell(int cx, int cz) {
        return this.ground[(cz + 1) * this.stride + (cx + 1)];
    }

    /**
     * Clip-Status einer (Nachbar-)Zelle — die Masken-Kante (geclippt ↔ ungeclippt) braucht
     * dieselben Skirts wie Regionsränder, sonst blitzen an der L0-Naht ~1 Block hohe
     * Schlitze durch (echte Säulen variieren gegenüber dem Zentrum-Sample). Außerhalb der
     * Region false: dort greift das Regionsrand-edge-Flag (Maske kennt nur eigene Chunks).
     */
    private boolean neighborClipped(int cx, int cz) {
        int n = this.cellCount;
        if (cx < 0 || cx >= n || cz < 0 || cz >= n) return false;
        return this.clipped[cz * n + cx];
    }

    /**
     * Biome-Tint eines Quads: bei GRASS/FOLIAGE-Typ liefert die Datenquelle die Biomfarbe am
     * Quad-Zentrum (region-lokale Koordinaten -> Welt), sonst bleibt der gebackene Tint.
     */
    private int tintFor(int baked, int tintType, float localX, float localZ) {
        if (tintType == BakedQuad.TINT_GRASS) {
            return this.source.grassTintAt(this.regionBaseX + (int) localX, this.regionBaseZ + (int) localZ);
        }
        if (tintType == BakedQuad.TINT_FOLIAGE) {
            return this.source.foliageTintAt(this.regionBaseX + (int) localX, this.regionBaseZ + (int) localZ);
        }
        return baked;
    }

    /** Sichtbare Oberkante einer Zelle: Fluide auf Quellhöhe (8/9), sonst Blockoberkante (+1). */
    private float topOf(long sample) {
        int h = LodDataSource.height(sample);
        return this.appearance.isFluid(LodDataSource.block(sample))
                ? h + FluidGeometry.SOURCE_HEIGHT : h + 1F;
    }

    /* ------------------------- Wände ------------------------- */

    /** Nord-/Süd-Terrain-Wände einer Zellreihe (Boden-Höhen), Runs entlang x. face = 2/3. */
    private void wallsAlongX(int cz, int dz, int face, boolean edge, int s, int maxRun) {
        int n = this.cellCount;
        int cx = 0;
        while (cx < n) {
            if (this.clipped[cz * n + cx]) {
                cx++;
                continue;
            }
            long sample = this.groundCell(cx, cz);
            long nSample = this.groundCell(cx, cz + dz);
            float top = this.topOf(sample);
            float nTop = this.topOf(nSample);
            /* Skirt an Regionsrand-Kanten IMMER; an Masken-Kanten (geclippter Nachbar = L0-Naht)
               nur bei tatsächlichem Höhenunterschied, dafür mit fester MASK_EDGE_SKIRT statt
               der vollen Regionsrand-Tiefe (der reale Versatz steckt schon in min(nTop, top)). */
            boolean nClipped = this.neighborClipped(cx, cz + dz);
            boolean maskFlush = nClipped && nTop == top;
            if ((!edge && !nClipped && nTop >= top) || maskFlush) {
                cx++;
                continue;
            }
            int run = 1;
            while (cx + run < n && run < maxRun && !this.clipped[cz * n + cx + run]
                    && this.groundCell(cx + run, cz) == sample && this.groundCell(cx + run, cz + dz) == nSample
                    && this.neighborClipped(cx + run, cz + dz) == nClipped) run++;

            float skirtDepth = edge ? this.edgeSkirt : (nClipped ? MASK_EDGE_SKIRT : 0F);
            float bottom = Math.max(0F, Math.min(nTop, top) - skirtDepth);
            float x0 = cx * s, x1 = (cx + run) * s;
            float z = (dz < 0 ? cz : cz + 1) * s;
            int block = LodDataSource.block(sample);
            if (face == 2) {
                this.emitWall(block, face, x1, z, x0, z, bottom, top);
            } else {
                this.emitWall(block, face, x0, z, x1, z, bottom, top);
            }
            cx += run;
        }
    }

    /** West-/Ost-Terrain-Wände einer Zellspalte (Boden-Höhen), Runs entlang z. face = 4/5. */
    private void wallsAlongZ(int cx, int dx, int face, boolean edge, int s, int maxRun) {
        int n = this.cellCount;
        int cz = 0;
        while (cz < n) {
            if (this.clipped[cz * n + cx]) {
                cz++;
                continue;
            }
            long sample = this.groundCell(cx, cz);
            long nSample = this.groundCell(cx + dx, cz);
            float top = this.topOf(sample);
            float nTop = this.topOf(nSample);
            /* Skirt an Regionsrand-Kanten IMMER; an Masken-Kanten (geclippter Nachbar = L0-Naht)
               nur bei tatsächlichem Höhenunterschied, dafür mit fester MASK_EDGE_SKIRT statt
               der vollen Regionsrand-Tiefe (der reale Versatz steckt schon in min(nTop, top)). */
            boolean nClipped = this.neighborClipped(cx + dx, cz);
            boolean maskFlush = nClipped && nTop == top;
            if ((!edge && !nClipped && nTop >= top) || maskFlush) {
                cz++;
                continue;
            }
            int run = 1;
            while (cz + run < n && run < maxRun && !this.clipped[(cz + run) * n + cx]
                    && this.groundCell(cx, cz + run) == sample && this.groundCell(cx + dx, cz + run) == nSample
                    && this.neighborClipped(cx + dx, cz + run) == nClipped) run++;

            float skirtDepth = edge ? this.edgeSkirt : (nClipped ? MASK_EDGE_SKIRT : 0F);
            float bottom = Math.max(0F, Math.min(nTop, top) - skirtDepth);
            float z0 = cz * s, z1 = (cz + run) * s;
            float x = (dx < 0 ? cx : cx + 1) * s;
            int block = LodDataSource.block(sample);
            if (face == 4) {
                this.emitWall(block, face, x, z0, x, z1, bottom, top);
            } else {
                this.emitWall(block, face, x, z1, x, z0, bottom, top);
            }
            cz += run;
        }
    }

    /**
     * Nord-/Süd-Wasser-Wände einer Zellreihe, Runs entlang x. Emittiert nur, wo eine
     * Fluid-Zelle an eine Zelle mit niedrigerer sichtbarer Oberkante grenzt — Wand vom
     * eigenen Wasserspiegel bis zur Nachbar-Oberkante, ohne Skirt (der Wasserspiegel ist
     * exakt; Fluid-Nachbarn mit gleichem Spiegel erzeugen keine Wand, auch an Regions-
     * und Masken-Kanten — deterministisch, beide Regionen sehen dieselben Randsamples).
     */
    private void waterWallsAlongX(int cz, int dz, int face, int s, int maxRun) {
        int n = this.cellCount;
        int cx = 0;
        while (cx < n) {
            if (this.clipped[cz * n + cx]) {
                cx++;
                continue;
            }
            long sample = this.cell(cx, cz);
            int block = LodDataSource.block(sample);
            if (!this.appearance.isFluid(block)) {
                cx++;
                continue;
            }
            long nSample = this.cell(cx, cz + dz);
            float top = this.topOf(sample);
            float nTop = this.topOf(nSample);
            if (nTop >= top) {
                cx++;
                continue;
            }
            int run = 1;
            while (cx + run < n && run < maxRun && !this.clipped[cz * n + cx + run]
                    && this.cell(cx + run, cz) == sample && this.cell(cx + run, cz + dz) == nSample) run++;

            float x0 = cx * s, x1 = (cx + run) * s;
            float z = (dz < 0 ? cz : cz + 1) * s;
            if (face == 2) {
                this.emitWall(block, face, x1, z, x0, z, nTop, top);
            } else {
                this.emitWall(block, face, x0, z, x1, z, nTop, top);
            }
            cx += run;
        }
    }

    /** West-/Ost-Wasser-Wände einer Zellspalte, Runs entlang z — s. {@link #waterWallsAlongX}. */
    private void waterWallsAlongZ(int cx, int dx, int face, int s, int maxRun) {
        int n = this.cellCount;
        int cz = 0;
        while (cz < n) {
            if (this.clipped[cz * n + cx]) {
                cz++;
                continue;
            }
            long sample = this.cell(cx, cz);
            int block = LodDataSource.block(sample);
            if (!this.appearance.isFluid(block)) {
                cz++;
                continue;
            }
            long nSample = this.cell(cx + dx, cz);
            float top = this.topOf(sample);
            float nTop = this.topOf(nSample);
            if (nTop >= top) {
                cz++;
                continue;
            }
            int run = 1;
            while (cz + run < n && run < maxRun && !this.clipped[(cz + run) * n + cx]
                    && this.cell(cx, cz + run) == sample && this.cell(cx + dx, cz + run) == nSample) run++;

            float z0 = cz * s, z1 = (cz + run) * s;
            float x = (dx < 0 ? cx : cx + 1) * s;
            if (face == 4) {
                this.emitWall(block, face, x, z0, x, z1, nTop, top);
            } else {
                this.emitWall(block, face, x, z1, x, z0, nTop, top);
            }
            cz += run;
        }
    }

    /* ------------------------- Quad-Emission ------------------------- */

    /**
     * Flaches Top-Quad auf absoluter Höhe y (CCW von oben, u=x / v=z wie BlockModels-Top).
     * {@code ao} = 4 Ecken-Multiplikatoren in Emissionsreihenfolge (x0z0, x0z1, x1z1, x1z0);
     * gemergte Runs sind per Merge-Bedingung uniform (alle 4 Werte gleich).
     */
    private void emitTop(int block, float[] ao, float x0, float z0, float x1, float z1, float y) {
        int layer = this.appearance.topLayer(block);
        int tint = this.tintFor(this.appearance.topTint(block), this.appearance.topTintType(block),
                (x0 + x1) * 0.5F, (z0 + z1) * 0.5F);
        float brightness = BlockModels.FACE_BRIGHTNESS[0];
        float u = x1 - x0, v = z1 - z0;
        /* Fluid-Tops gehen in den Translucent-Puffer (transparente Wasserfläche); alles
           andere bleibt opak. Wände an Fluid-Zellen sind analog transluzent (s. emitWall). */
        boolean fluidTop = this.appearance.isFluid(block);

        this.ensureCapacity(fluidTop);
        this.putVertex(fluidTop, x0, y, z0, 0F, 0F, layer, brightness * ao[0], tint);
        this.putVertex(fluidTop, x0, y, z1, 0F, v, layer, brightness * ao[1], tint);
        this.putVertex(fluidTop, x1, y, z1, u, v, layer, brightness * ao[2], tint);
        this.putVertex(fluidTop, x1, y, z0, u, 0F, layer, brightness * ao[3], tint);

        if (y > this.maxTop) this.maxTop = y;
        if (y < this.minBottom) this.minBottom = y;
    }

    /**
     * Analytische Heightmap-AO einer EINZELZELLE (Hillshading statt Voxel-Occlusion): pro
     * Ecke zwei Kanten-Nachbarn + 1 Diagonal-Nachbar, analog {@link ChunkMesher}s AO-Schema,
     * aber der Test ist "Nachbarzelle höher als die eigene?" statt "Nachbar opak?". Nutzt
     * ausschließlich den bereits gesampelten (n+2)×(n+2)-Randring — kein Zusatz-Sampling.
     * Reihenfolge in {@code out}: (cx,cz), (cx,cz+1), (cx+1,cz+1), (cx+1,cz) — wie emitTop.
     */
    private void computeCellAo(int cx, int cz, float ownTop, float[] out) {
        out[0] = this.cornerAo(ownTop, cx - 1, cz, cx, cz - 1, cx - 1, cz - 1); // NW
        out[1] = this.cornerAo(ownTop, cx - 1, cz, cx, cz + 1, cx - 1, cz + 1); // SW
        out[2] = this.cornerAo(ownTop, cx + 1, cz, cx, cz + 1, cx + 1, cz + 1); // SE
        out[3] = this.cornerAo(ownTop, cx + 1, cz, cx, cz - 1, cx + 1, cz - 1); // NE
    }

    /** true, wenn alle 4 Ecken-AO-Werte der Zelle exakt {@code value} sind (Merge-Kriterium). */
    private boolean cellAoUniform(int cx, int cz, float ownTop, float value) {
        return this.cornerAo(ownTop, cx - 1, cz, cx, cz - 1, cx - 1, cz - 1) == value
                && this.cornerAo(ownTop, cx - 1, cz, cx, cz + 1, cx - 1, cz + 1) == value
                && this.cornerAo(ownTop, cx + 1, cz, cx, cz + 1, cx + 1, cz + 1) == value
                && this.cornerAo(ownTop, cx + 1, cz, cx, cz - 1, cx + 1, cz - 1) == value;
    }

    /* Vergleicht bewusst BODEN-Höhen (ground): Wasser wirft kein AO, und der Meeresboden
       bekommt sein eigenes Hillshading aus den Grund-Höhen der Nachbarzellen. */
    private float cornerAo(float ownTop, int edgeXCx, int edgeXCz, int edgeZCx, int edgeZCz,
                           int diagCx, int diagCz) {
        boolean edgeX = this.topOf(this.groundCell(edgeXCx, edgeXCz)) > ownTop;
        boolean edgeZ = this.topOf(this.groundCell(edgeZCx, edgeZCz)) > ownTop;
        boolean diag = this.topOf(this.groundCell(diagCx, diagCz)) > ownTop;
        int level = 3 - (edgeX ? 1 : 0) - (edgeZ ? 1 : 0) - (diag ? 1 : 0);
        return 0.4F + level * 0.2F;
    }

    /**
     * Senkrechte Wand von bottom bis top (absolut) zwischen den Bodenpunkten A=(xa,za) und
     * B=(xb,zb) (A→B = u-Richtung; Aufrufer wählt die CCW-Sicht von außen). v=0 an der
     * Oberkante (Textur-Oben = Face-Oben, wie BlockModels).
     */
    private void emitWall(int block, int face, float xa, float za, float xb, float zb,
                          float bottom, float top) {
        int layer = this.appearance.sideLayer(block);
        int tint = this.tintFor(this.appearance.sideTint(block), this.appearance.sideTintType(block),
                (xa + xb) * 0.5F, (za + zb) * 0.5F);
        float brightness = BlockModels.FACE_BRIGHTNESS[face];
        float u = Math.abs(xb - xa) + Math.abs(zb - za);
        float v = Math.min(top - bottom, MAX_MERGE_BLOCKS);

        /* Wände an Fluid-Zellen sind die sichtbare Seite eines Wasserkörpers (z.B. Seeufer-
           Abbruch) und daher ebenfalls transluzent — sonst dominieren sie aus Nähe/niedriger
           Position (nah an oder unter einer LOD-Wasserkante) den Bildschirm als große opake
           Fläche. Wände an festem Terrain bleiben opak. */
        boolean fluidWall = this.appearance.isFluid(block);

        /* Koplanares Seiten-Overlay (getönter Grasrand) ZUERST emittieren: identische Vertices
           im selben Opaque-Draw ⇒ identische Tiefe (GL-Invarianz); die danach emittierte
           Basis-Wand verliert den strikten Tiefentest genau dort, wo das Overlay nicht
           discarded wurde (u_AlphaCutoff 0.5 gilt auch im LOD-OPAQUE-Segment). Gegenstück zur
           koplanaren Or-Equal-Lösung des ChunkMesher — bewusst OHNE Offset und ohne eigenen
           Pass; die Reihenfolge Overlay-vor-Basis ist tragend. */
        int overlayLayer = this.appearance.sideOverlayLayer(block);
        if (!fluidWall && overlayLayer >= 0) {
            int overlayTint = this.tintFor(this.appearance.sideOverlayTint(block),
                    this.appearance.sideOverlayTintType(block), (xa + xb) * 0.5F, (za + zb) * 0.5F);
            this.ensureCapacity(false);
            this.putVertex(false, xa, bottom, za, 0F, v, overlayLayer, brightness, overlayTint);
            this.putVertex(false, xb, bottom, zb, u, v, overlayLayer, brightness, overlayTint);
            this.putVertex(false, xb, top, zb, u, 0F, overlayLayer, brightness, overlayTint);
            this.putVertex(false, xa, top, za, 0F, 0F, overlayLayer, brightness, overlayTint);
        }

        this.ensureCapacity(fluidWall);
        this.putVertex(fluidWall, xa, bottom, za, 0F, v, layer, brightness, tint);
        this.putVertex(fluidWall, xb, bottom, zb, u, v, layer, brightness, tint);
        this.putVertex(fluidWall, xb, top, zb, u, 0F, layer, brightness, tint);
        this.putVertex(fluidWall, xa, top, za, 0F, 0F, layer, brightness, tint);

        if (top > this.maxTop) this.maxTop = top;
        if (bottom < this.minBottom) this.minBottom = bottom;
    }

    private void ensureCapacity(boolean translucent) {
        if (translucent) {
            if (this.viTranslucent + QUAD_INTS > this.outTranslucent.length) {
                this.outTranslucent = Arrays.copyOf(this.outTranslucent, this.outTranslucent.length * 2);
            }
        } else {
            if (this.viOpaque + QUAD_INTS > this.outOpaque.length) {
                this.outOpaque = Arrays.copyOf(this.outOpaque, this.outOpaque.length * 2);
            }
        }
    }

    /**
     * Packt einen Vertex ins Chunk-Format (Konstanten aus {@link ChunkMesher}, Bias +1);
     * y wird relativ zu {@link #yBase} gepackt (Renderer addiert yBase im Draw-Offset).
     * Clamp als Sicherheitsnetz gegen Format-Überlauf (wie ChunkMesher.fixedPos).
     * Der 5. Int ist wie bei {@link ChunkMesher#VERTEX_SIZE} für farbiges Licht reserviert
     * und wird aktuell nicht befüllt.
     */
    private void putVertex(boolean translucent, float x, float y, float z, float u, float v,
                           int layer, float brightness, int tint) {
        int px = (int) ((x + 1F) * this.posScale + 0.5F);
        int py = Math.clamp((int) ((y - this.yBase + 1F) * this.posScale + 0.5F), 0, 0xFFFF);
        int pz = (int) ((z + 1F) * this.posScale + 0.5F);
        int pu = (int) ((u + 1F) * ChunkMesher.UV_SCALE + 0.5F);
        int pv = (int) ((v + 1F) * ChunkMesher.UV_SCALE + 0.5F);
        int r = Math.clamp((int) (((tint >> 16) & 0xFF) * brightness + 0.5F), 0, 255);
        int g = Math.clamp((int) (((tint >> 8) & 0xFF) * brightness + 0.5F), 0, 255);
        int b = Math.clamp((int) ((tint & 0xFF) * brightness + 0.5F), 0, 255);
        int[] buf = translucent ? this.outTranslucent : this.outOpaque;
        int i = translucent ? this.viTranslucent : this.viOpaque;
        buf[i++] = px | (py << 16);
        buf[i++] = pz | (pu << 16);
        buf[i++] = pv | (layer << 16);
        buf[i++] = r | (g << 8) | (b << 16);
        buf[i++] = 0; // reserviert für farbiges Licht (Phase Lichtsystem)
        if (translucent) this.viTranslucent = i; else this.viOpaque = i;
    }
}
