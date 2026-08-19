package de.skyengine.game.world.lod;

import de.skyengine.core.settings.GameSettings;
import de.skyengine.game.world.block.Blocks;
import de.skyengine.game.world.block.model.BakedQuad;
import de.skyengine.game.world.block.model.BlockModels;
import de.skyengine.game.world.chunk.ChunkMesher;
import de.skyengine.game.world.chunk.FluidGeometry;
import de.skyengine.game.world.lod.LodManager.LodClipSnapshot;
import de.skyengine.game.world.lod.LodManager.LodMeshResult;
import de.skyengine.game.world.lod.LodManager.LodNeighborSnapshot;

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
 * <p><b>Determinismus:</b> Reguläre Zellen liegen auf einem globalen Raster. Der persistente
 * Spaltenpfad verändert die grobe Kontur an Auflösungsgrenzen nicht, sondern schließt sie
 * segmentweise gegen die unmittelbar benachbarte feinere Kontur. Der kompakte
 * Heightmap-Fallback behält seine konservativen Rand-Skirts.
 *
 * <p><b>Clipping:</b> übersprungen werden genau die Zellen, deren Chunk laut 16-Bit-Maske
 * des Jobs gerade echtes Terrain zeigt — LOD ersetzt Chunks exakt dort, wo keine sind.
 *
 * <p><b>yBase:</b> Vertices werden relativ zu einer Regionsbasis gepackt (u16 trägt nur
 * ~254 Blöcke Spanne, Gipfel gehen höher); der Renderer schiebt per Draw-Offset zurück.
 *
 * <p>Läuft auf den Chunk-Workern, liest ausschließlich die {@link LodDataSource}. Ausgabe:
 * gepacktes 16-Byte-Vertex-Format des {@link ChunkMesher}. Eine Instanz pro Worker-Thread.
 * Gegen Quad-Explosion: 2D-Greedy-Merge der Tops (Breite entlang x, dann Höhe entlang z, wie
 * im ChunkMesher-Greedy) und 1D-Runs der Wände entlang ihrer Kante (die zweite Quad-Dimension
 * ist dort bereits die Höhe), Deckel {@link #MAX_MERGE_BLOCKS} je Achse.
 */
public final class LodMesher {

    /** Kantenlänge einer LOD-Region in Blöcken (4x4 Chunks, fix über alle Level). */
    public static final int REGION_BLOCKS = 128;

    /** Halbe Diagonale einer Region — Toleranz für Kreis-Überlappungstests. */
    public static final float HALF_DIAG = 90.6F;

    /* Merge-/UV-Deckel in Blöcken (UV-Fixed-Point 6.10 trägt max ~63; 32 lässt Reserve). */
    private static final int MAX_MERGE_BLOCKS = 32;

    /* Nur für den kompakten Heightmap-Fallback: BASE·2^Level, gedeckelt. Herleitung MAX:
       Y-Feld = u16, max y_rel ≈ 254,99;
       nutzbare Spanne nach Bias + yBase-Marge ≈ 253 = Relief + Skirt + 3. Bei Relief_max ≈ 200
       pro Region (Mountain-Ridged) bleibt Skirt ≤ 50 → 48 (deckt auch Stride-16-Übergänge an
       steilen Hängen, ~40 Blöcke). */
    private static final int BASE_SKIRT = 16;
    private static final int MAX_SKIRT = 48;

    /* Face-Indizes wie BlockModels: 0=top, 2=north(-z), 3=south(+z), 4=west(-x), 5=east(+x) */

    private static final int QUAD_INTS = 4 * ChunkMesher.VERTEX_SIZE;

    /* Neutral-AO für Wasser-Tops (eben, kein Hillshading) — nur lesend verwendet. */
    private static final float[] AO_NONE = {1F, 1F, 1F, 1F};

    /* --- Wiederverwendete Puffer (eine Instanz pro Worker-Thread) --- */
    private long[] cells = new long[0];        // (n+2)² Oberflächen-Samples inkl. Randring
    /* Boden-Samples (ohne Wasser): für Nicht-Fluid-Zellen identisch mit cells; für
       Fluid-Zellen der feste Boden darunter — Basis für Terrain-Tops/-Wände/AO/yBase. */
    private long[] ground = new long[0];
    private LodColumn[] columns = new LodColumn[0];
    private boolean[] clipped = new boolean[0];
    private boolean[] transitionEdges = new boolean[0]; // vier Faces × tangentiale Zellen
    /* Merge-Marker der Top-Pässe (2D-Greedy): true = Zelle schon in ein Quad gemerged;
       wird vor Terrain- (3a) und Wasser-Pass (3b) jeweils zurückgesetzt. */
    private boolean[] consumed = new boolean[0];
    /* Getrennte Ausgabepuffer: Fluid-Top-Quads -> translucent (transparent, eigene Arena/
       eigener Draw-Call im Renderer), alles andere (Terrain-Tops + Wände sowie die nur im
       Heightmap-Fallback verbleibenden Skirts,
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
    private LodClipSnapshot clipSnapshot = LodClipSnapshot.centerOnly(0);
    private LodNeighborSnapshot neighborSnapshot = LodNeighborSnapshot.sameLevel(1);
    private boolean columnWorldBottom;
    private int regionBaseX, regionBaseZ;      // Weltkoordinaten-Ursprung der Region
    private float minBottom, maxTop;           // absolut (fürs Frustum-AABB)
    private final float[] aoScratch = new float[4]; // wiederverwendet: P1,P2,P3,P4 pro Top-Quad
    private final float[] aoFlatScratch = new float[4]; // Scratch für cellAoFlat (Merge-Kandidaten)
    private boolean flatAo;                    // reservierter Vergleichsmodus; aktuell bewusst aus
    private boolean columnAo;                  // AO für Seiten des Spaltenpfads
    private long lastSamplingNanos, lastGeometryNanos;

    /* Optionaler Debug-Statistik-Sink: NUR vom LodQuadCensus gesetzt. In der laufenden Engine
       bleibt dies null (jede Zählung ist per if(stats != null) geguardet → kein Overhead, keine
       Verhaltens-/Layout-Änderung). Akkumuliert über alle Regionen eines Zensus-Laufs. */
    private LodMeshStats stats;

    /* Deckel der Quantisierungs-Stufe. Ohne Deckel quantisiert L4/L5 auf 16/32 Blöcke — im
       Fenster-A/B (Render-Distanz testweise 4, dadurch L4/L5 nah am Spieler) kippt das Terrain
       damit sichtbar in grobe Plateaus/Mesa-Wände. 8 ist die größte Stufe, die bei der
       Ship-Config (rd=16/lodMax=128 ⇒ nur L1..L3, Stride ≤ 8) im A/B optisch NICHT auffiel.
       Bei rd=16/lodMax=128 ist der Deckel damit wirkungslos (Stride ist ohnehin ≤ 8) — er greift
       nur, wenn hohe Level existieren (großes lodMax oder kleine Render-Distanz). */
    private static final int MAX_QUANT_STRIDE = 8;

    /** Debug: aktiviert die Quad-Statistik (LodQuadCensus). In-Engine nicht aufrufen. */
    public void setStats(LodMeshStats stats) {
        this.stats = stats;
    }

    /** Skirt-Tiefe an Regionsrand-Kanten, wächst mit der Zellgröße (s. MAX_SKIRT-Herleitung). */
    private static int edgeSkirtOf(int level) {
        return Math.min(BASE_SKIRT << level, MAX_SKIRT);
    }

    /* Konservative Arena-Schätzung: Terrain-Tops, Relief-/Skirt-Wände sowie zusätzliche
       Strukturintervalle des Spaltenpfads. Bewusst mit Reserve, damit die erste Füllung
       ohne stufenweises Arena-Wachstum auskommt. */
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
        return this.mesh(source, appearance, config, level, sizeRegions, rx, rz, epoch,
                LodClipSnapshot.centerOnly(mask), ax, az);
    }

    public LodMeshResult mesh(LodDataSource source, LodBlockAppearance appearance, LodConfig config,
                              int level, int sizeRegions, int rx, int rz, int epoch,
                              LodClipSnapshot clipSnapshot, int ax, int az) {
        return this.mesh(source, appearance, config, level, sizeRegions, rx, rz, epoch,
                clipSnapshot, neighborSnapshotFromAnchor(config, rx, rz, ax, az), ax, az);
    }

    public LodMeshResult mesh(LodDataSource source, LodBlockAppearance appearance, LodConfig config,
                              int level, int sizeRegions, int rx, int rz, int epoch,
                              LodClipSnapshot clipSnapshot, LodNeighborSnapshot neighborSnapshot,
                              int ax, int az) {
        int mask = clipSnapshot.centerMask();
        this.clipSnapshot = clipSnapshot;
        this.neighborSnapshot = neighborSnapshot;
        this.lastSamplingNanos = 0;
        this.lastGeometryNanos = 0;
        this.columnAo = false;
        if (source.hasColumns()) {
            return this.meshColumns(source, appearance, config, level, sizeRegions, rx, rz,
                    epoch, clipSnapshot, neighborSnapshot, ax, az);
        }
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
            return new LodMeshResult(level, rx, rz, sizeRegions, epoch, mask, clipSnapshot,
                    neighborSnapshot, 0,
                    new int[0], new int[0], 0F, 0F);
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
                long sample = this.sampleCell(source, config, wx, wz, s, rx, rz, sizeRegions, ax, az);
                long groundSample = this.appearance.isFluid(LodDataSource.block(sample))
                        ? this.sampleGroundCell(source, config, wx, wz, s, rx, rz, sizeRegions, ax, az) : sample;
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
           durch das transluzente Wasser sichtbar). 2D-Greedy: Breite entlang x, dann Höhe
           entlang z (wie Wasser-Tops in 3b / ChunkMesher-Greedy). Merge nur bei uniformem
           UND gleichem AO — wie im ChunkMesher-Greedy: uneinheitliche Zellen einzeln mit
           per-Ecke-AO, sonst interpoliert die GPU die Eckwerte als Gradient-Bänder über
           die gemergte Fläche. */
        /* AO aus (Setting): neutral hell (1.0) + frei mergen nach Block+Höhe — exakt wie im
           ChunkMesher (aoIdx 3 = 1.0), damit der Look über die LOD-Grenze konsistent bleibt.
           Live gelesen; der LodManager bumpt bei AO-Toggle die Epoche → alle Regionen neu. */
        boolean useAo = GameSettings.get().ambientOcclusion;

        /* AO bleibt auch im Fernring eckgenau. Abflachung erzeugt an Greedy-Grenzen die
           sichtbaren harten Helligkeitsbaender, die schwerer wiegen als weitere Top-Quads. */
        this.flatAo = false;

        /* Debug: Merge-Grenzen der Terrain-Tops erfassen (ordnungsunabhängig, s. LodMeshStats). */
        if (this.stats != null) this.recordSeams(n, useAo);

        int maxRun = Math.max(1, MAX_MERGE_BLOCKS / s);
        if (this.consumed.length < n * n) this.consumed = new boolean[n * n];
        else Arrays.fill(this.consumed, 0, n * n, false);
        for (int cz = 0; cz < n; cz++) {
            int cx = 0;
            while (cx < n) {
                int idx = cz * n + cx;
                if (this.clipped[idx] || this.consumed[idx]) {
                    cx++;
                    continue;
                }
                long g = this.groundCell(cx, cz);
                /* Leere Spalten (Void-Rand importierter Welten): kein Terrain — nichts emittieren. */
                if (LodDataSource.block(g) == Blocks.AIR) {
                    cx++;
                    continue;
                }
                float top = this.topOf(g);
                int skyLight = this.skyLightAt(this.cell(cx, cz), top);
                float[] ao;
                boolean uniform;
                if (useAo && this.flatAo) {
                    ao = this.aoScratch;
                    ao[0] = ao[1] = ao[2] = ao[3] = this.cellAoFlat(cx, cz, top);
                    uniform = true;
                } else if (useAo) {
                    ao = this.aoScratch;
                    this.computeCellAo(cx, cz, top, ao);
                    uniform = ao[0] == ao[1] && ao[1] == ao[2] && ao[2] == ao[3];
                } else {
                    ao = AO_NONE;
                    uniform = true;
                }

                int w = 1, h = 1;
                if (uniform) {
                    while (cx + w < n && w < maxRun && !this.clipped[cz * n + cx + w]
                            && !this.consumed[cz * n + cx + w] && this.groundCell(cx + w, cz) == g
                            && this.skyLightAt(this.cell(cx + w, cz), top) == skyLight
                            && (!useAo || this.aoMergeable(cx + w, cz, top, ao[0]))) w++;

                    /* Gleiches Sample ⇒ gleiche Oberkante — top gilt auch für die Kandidatenzeile */
                    expand:
                    while (cz + h < n && h < maxRun) {
                        for (int i = 0; i < w; i++) {
                            int j = (cz + h) * n + (cx + i);
                            if (this.clipped[j] || this.consumed[j] || this.groundCell(cx + i, cz + h) != g
                                    || this.skyLightAt(this.cell(cx + i, cz + h), top) != skyLight
                                    || (useAo && !this.aoMergeable(cx + i, cz + h, top, ao[0]))) {
                                break expand;
                            }
                        }
                        h++;
                    }

                    /* Zeile 0 überspringt cx += w; nur die Folgezeilen brauchen den Marker */
                    for (int dz = 1; dz < h; dz++) {
                        for (int dx = 0; dx < w; dx++) this.consumed[(cz + dz) * n + (cx + dx)] = true;
                    }
                }
                this.emitTop(LodDataSource.block(g), ao, cx * s, cz * s,
                        (cx + w) * s, (cz + h) * s, top, skyLight);
                cx += w;
            }
        }

        /* 3b. Wasser-Tops über die Oberflächen-Samples (nur Fluid-Zellen), ohne AO —
           Wasserflächen sind eben; AO würde fleckig und zerstört den Merge. 2D-Greedy
           (Breite entlang x, dann Höhe entlang z, wie im ChunkMesher-Greedy) — 1D erzeugte
           lange dünne Streifen; flache Ozeanflächen werden so zu wenigen großen Rechtecken. */
        Arrays.fill(this.consumed, 0, n * n, false); // von 3a wiederverwendet
        for (int cz = 0; cz < n; cz++) {
            int cx = 0;
            while (cx < n) {
                int idx = cz * n + cx;
                if (this.clipped[idx] || this.consumed[idx]) {
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
                        && !this.consumed[cz * n + cx + w] && this.cell(cx + w, cz) == sample) w++;

                int h = 1;
                expand:
                while (cz + h < n && h < maxRun) {
                    for (int i = 0; i < w; i++) {
                        int j = (cz + h) * n + (cx + i);
                        if (this.clipped[j] || this.consumed[j] || this.cell(cx + i, cz + h) != sample) {
                            break expand;
                        }
                    }
                    h++;
                }

                for (int dz = 0; dz < h; dz++) {
                    for (int dx = 0; dx < w; dx++) this.consumed[(cz + dz) * n + (cx + dx)] = true;
                }
                this.emitTop(block, AO_NONE, cx * s, cz * s,
                        (cx + w) * s, (cz + h) * s, this.topOf(sample), 15);
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
        return new LodMeshResult(level, rx, rz, this.sizeRegions, epoch, mask, clipSnapshot,
                neighborSnapshot, this.yBase,
                opaqueData, translucentData, minY, maxY);
    }

    /** Mehrschichtiger Greedy-Pfad für persistente L0-L5-Spalten. */
    private LodMeshResult meshColumns(LodDataSource source, LodBlockAppearance appearance, LodConfig config,
                                      int level, int sizeRegions, int rx, int rz, int epoch,
                                      LodClipSnapshot clipSnapshot,
                                      LodNeighborSnapshot neighborSnapshot,
                                      int ax, int az) {
        int mask = clipSnapshot.centerMask();
        int s = config.cellSize(level);
        int n = sizeRegions * REGION_BLOCKS / s;
        this.stride = n + 2;
        this.cellCount = n;
        this.sizeRegions = sizeRegions;
        this.appearance = appearance;
        this.source = source;
        this.columnWorldBottom = source.hasWorldBottom();
        this.posScale = posScaleFor(sizeRegions);
        this.regionBaseX = rx * REGION_BLOCKS;
        this.regionBaseZ = rz * REGION_BLOCKS;
        if (mask == 0xFFFF) {
            return new LodMeshResult(level, rx, rz, sizeRegions, epoch, mask, clipSnapshot,
                    neighborSnapshot, 0,
                    new int[0], new int[0], 0F, 0F);
        }
        int sampleCount = this.stride * this.stride;
        if (this.columns.length < sampleCount) this.columns = new LodColumn[sampleCount];
        long samplingStarted = System.nanoTime();
        int minY = Integer.MAX_VALUE;
        source.sampleColumns(this.regionBaseX - s, this.regionBaseZ - s, s,
                this.stride, this.stride, this.columns, 0, this.stride);
        for (int index = 0; index < sampleCount; index++) {
            LodColumn column = this.columns[index];
            for (int i = 0; i < column.size(); i++) {
                minY = Math.min(minY, LodColumn.minY(column.interval(i)));
            }
        }
        if (this.clipped.length < n * n) this.clipped = new boolean[n * n];
        if (mask == 0) {
            Arrays.fill(this.clipped, 0, n * n, false);
        } else {
            int cellsPerChunk = 32 / s;
            for (int z = 0; z < n; z++) for (int x = 0; x < n; x++) {
                int bit = (z / cellsPerChunk) * 4 + x / cellsPerChunk;
                this.clipped[z * n + x] = (mask & 1 << bit) != 0;
            }
        }
        this.prepareColumnTransitions(source, config, level, ax, az, s, n);
        this.lastSamplingNanos = System.nanoTime() - samplingStarted;
        long geometryStarted = System.nanoTime();
        this.yBase = Math.max(0, (minY == Integer.MAX_VALUE ? 0 : minY) - 2);
        if (this.consumed.length < n * n) this.consumed = new boolean[n * n];
        this.viOpaque = this.viTranslucent = 0;
        this.minBottom = Float.MAX_VALUE;
        this.maxTop = -Float.MAX_VALUE;
        int maxRun = Math.max(1, MAX_MERGE_BLOCKS / s);
        boolean useAo = GameSettings.get().ambientOcclusion;
        this.columnAo = useAo;
        /* Wie im Heightmap-Pfad bleibt AO auf allen Levels eckgenau. */
        this.flatAo = false;

        for (int intervalIndex = 0; intervalIndex < LodColumn.MAX_INTERVALS; intervalIndex++) {
            Arrays.fill(this.consumed, 0, n * n, false);
            for (int z = 0; z < n; z++) for (int x = 0; x < n;) {
                int cellIndex = z * n + x;
                LodColumn column = this.column(x, z);
                if (this.clipped[cellIndex] || this.consumed[cellIndex] || intervalIndex >= column.size()
                        || !this.topExposed(column, intervalIndex)) {
                    x++;
                    continue;
                }
                long interval = column.interval(intervalIndex);
                int block = LodColumn.state(interval);
                float top = this.appearance.isFluid(block)
                        ? LodColumn.maxY(interval) - 1 + FluidGeometry.SOURCE_HEIGHT
                        : LodColumn.maxY(interval);
                int skyLight = this.columnSkyLight(column, top);
                boolean shadeAo = useAo && !this.appearance.isFluid(block);
                float[] ao = AO_NONE;
                boolean uniform = true;
                if (shadeAo) {
                    ao = this.aoScratch;
                    if (this.flatAo) {
                        ao[0] = ao[1] = ao[2] = ao[3] = this.columnCellAoFlat(x, z, top);
                    } else {
                        this.computeColumnCellAo(x, z, top, ao);
                        uniform = ao[0] == ao[1] && ao[1] == ao[2] && ao[2] == ao[3];
                    }
                }
                int width = 1, height = 1;
                if (uniform) {
                    while (x + width < n && width < maxRun
                            && this.columnTopFaceMatches(x + width, z, intervalIndex, interval,
                            skyLight, shadeAo, ao[0])) width++;
                    topExpand:
                    while (z + height < n && height < maxRun) {
                        for (int dx = 0; dx < width; dx++) {
                            if (!this.columnTopFaceMatches(x + dx, z + height, intervalIndex,
                                    interval, skyLight, shadeAo, ao[0])) break topExpand;
                        }
                        height++;
                    }
                }
                for (int dz = 0; dz < height; dz++) for (int dx = 0; dx < width; dx++) {
                    this.consumed[(z + dz) * n + x + dx] = true;
                }
                this.emitTop(block, ao, x * s, z * s, (x + width) * s, (z + height) * s,
                        top, skyLight);
                x += width;
            }

            Arrays.fill(this.consumed, 0, n * n, false);
            for (int z = 0; z < n; z++) for (int x = 0; x < n;) {
                int cellIndex = z * n + x;
                LodColumn column = this.column(x, z);
                if (this.clipped[cellIndex] || this.consumed[cellIndex] || intervalIndex >= column.size()
                        || !this.bottomExposed(column, intervalIndex)) {
                    x++;
                    continue;
                }
                long interval = column.interval(intervalIndex);
                /* Eine Unterseite sieht den Himmel nie direkt: Bereits das eigene massive
                   Intervall schirmt sie ab. Seitliche Lichtausbreitung wird im LOD-Pfad
                   bewusst nicht simuliert, daher bleibt die konservative Näherung 0. */
                int skyLight = 0;
                int width = 1;
                while (x + width < n && width < maxRun
                        && this.columnFaceMatches(x + width, z, intervalIndex,
                        interval, false, skyLight)) width++;
                int height = 1;
                bottomExpand:
                while (z + height < n && height < maxRun) {
                    for (int dx = 0; dx < width; dx++) {
                        if (!this.columnFaceMatches(x + dx, z + height, intervalIndex,
                                interval, false, skyLight)) break bottomExpand;
                    }
                    height++;
                }
                for (int dz = 0; dz < height; dz++) for (int dx = 0; dx < width; dx++) {
                    this.consumed[(z + dz) * n + x + dx] = true;
                }
                this.emitBottom(LodColumn.state(interval), x * s, z * s,
                        (x + width) * s, (z + height) * s, LodColumn.minY(interval), skyLight);
                x += width;
            }
        }

        for (int z = 0; z < n; z++) {
            this.columnWallsAlongX(z, -1, 2, s, maxRun);
            this.columnWallsAlongX(z, 1, 3, s, maxRun);
        }
        for (int x = 0; x < n; x++) {
            this.columnWallsAlongZ(x, -1, 4, s, maxRun);
            this.columnWallsAlongZ(x, 1, 5, s, maxRun);
        }
        this.columnMeasuredTransitions(source, config, level, s, n, ax, az);
        int[] opaque = this.viOpaque == 0 ? new int[0] : Arrays.copyOf(this.outOpaque, this.viOpaque);
        int[] translucent = this.viTranslucent == 0 ? new int[0]
                : Arrays.copyOf(this.outTranslucent, this.viTranslucent);
        boolean empty = this.viOpaque == 0 && this.viTranslucent == 0;
        this.appearance = null;
        this.source = null;
        this.columnWorldBottom = false;
        this.lastGeometryNanos = System.nanoTime() - geometryStarted;
        return new LodMeshResult(level, rx, rz, sizeRegions, epoch, mask, clipSnapshot,
                neighborSnapshot, this.yBase,
                opaque, translucent, empty ? 0F : this.minBottom, empty ? 0F : this.maxTop);
    }

    long lastSamplingNanos() { return this.lastSamplingNanos; }
    long lastGeometryNanos() { return this.lastGeometryNanos; }

    private boolean columnFaceMatches(int x, int z, int index, long interval,
                                      boolean top, int skyLight) {
        int ci = z * this.cellCount + x;
        if (this.clipped[ci] || this.consumed[ci]) return false;
        LodColumn column = this.column(x, z);
        if (index >= column.size() || column.interval(index) != interval
                || !(top ? this.topExposed(column, index) : this.bottomExposed(column, index))) return false;
        if (!top) return skyLight == 0;
        float y = this.appearance.isFluid(LodColumn.state(interval))
                ? LodColumn.maxY(interval) - 1 + FluidGeometry.SOURCE_HEIGHT
                : LodColumn.maxY(interval);
        return this.columnSkyLight(column, y) == skyLight;
    }

    /** Top-Merge-Kriterium inklusive AO; Bottom-Flächen verwenden weiterhin columnFaceMatches. */
    private boolean columnTopFaceMatches(int x, int z, int index, long interval,
                                         int skyLight, boolean useAo, float ao) {
        if (!this.columnFaceMatches(x, z, index, interval, true, skyLight)) return false;
        if (!useAo) return true;
        float top = LodColumn.maxY(interval);
        return this.flatAo ? this.columnCellAoFlat(x, z, top) == ao
                : this.columnCellAoUniform(x, z, top, ao);
    }

    private boolean topExposed(LodColumn column, int index) {
        return index + 1 >= column.size()
                || LodColumn.minY(column.interval(index + 1)) > LodColumn.maxY(column.interval(index))
                || this.appearance.isTranslucent(LodColumn.state(column.interval(index + 1)));
    }

    private boolean bottomExposed(LodColumn column, int index) {
        long interval = column.interval(index);
        if (this.appearance.isFluid(LodColumn.state(interval))) return false;
        if (LodColumn.minY(interval) == 0) return this.columnWorldBottom;
        if (!LodColumn.landmark(interval)) return false;
        return index == 0
                || LodColumn.maxY(column.interval(index - 1)) < LodColumn.minY(interval)
                || this.appearance.isTranslucent(LodColumn.state(column.interval(index - 1)));
    }

    private int columnSkyLight(LodColumn column, float y) {
        int depth = 0;
        for (int i = 0; i < column.size(); i++) {
            long interval = column.interval(i);
            if (LodColumn.maxY(interval) <= y
                    || !this.appearance.attenuatesSkyLight(LodColumn.state(interval))) continue;
            depth += Math.max(0, LodColumn.maxY(interval) - Math.max((int) Math.floor(y), LodColumn.minY(interval)));
        }
        return Math.clamp(15 - depth, 0, 15);
    }

    private LodColumn column(int x, int z) {
        return this.columns[(z + 1) * this.stride + x + 1];
    }

    private record TerrainProfile(int state, int top) {}

    /**
     * Markiert ausschließlich Auflösungsgrenzen und stellt fein aufgelöste Vergleichs-Proxies
     * für AO und Seitenlicht bereit. Sichtbare grobe Spalten werden hier bewusst nicht
     * verändert; die Konturen werden später geometrisch miteinander vernäht.
     */
    private void prepareColumnTransitions(LodDataSource source, LodConfig config, int level,
                                          int ax, int az, int s, int n) {
        if (this.transitionEdges.length < 4 * n) this.transitionEdges = new boolean[4 * n];
        Arrays.fill(this.transitionEdges, 0, 4 * n, false);

        for (int i = 0; i < n; i++) {
            if (this.boundaryNeighborClipped(4, i, s) || resolutionTransition(level,
                    this.boundaryNeighborLevel(config, 4, i, s, ax, az))) {
                this.transitionEdges[(4 - 2) * n + i] = true;
            }
            if (this.boundaryNeighborClipped(5, i, s) || resolutionTransition(level,
                    this.boundaryNeighborLevel(config, 5, i, s, ax, az))) {
                this.transitionEdges[(5 - 2) * n + i] = true;
            }
            if (this.boundaryNeighborClipped(2, i, s) || resolutionTransition(level,
                    this.boundaryNeighborLevel(config, 2, i, s, ax, az))) {
                this.transitionEdges[i] = true;
            }
            if (this.boundaryNeighborClipped(3, i, s) || resolutionTransition(level,
                    this.boundaryNeighborLevel(config, 3, i, s, ax, az))) {
                this.transitionEdges[n + i] = true;
            }
        }

        this.replaceClippedBoundaryProxies(source, s, n);
        this.replaceRegionHalos(source, config, level, s, n, ax, az);
    }

    private int boundaryNeighborLevel(LodConfig config, int face, int tangent, int s,
                                      int ax, int az) {
        return this.neighborSnapshot.level(face);
    }

    private boolean boundaryNeighborClipped(int face, int tangent, int s) {
        int chunkAlongEdge = Math.clamp(tangent * s / 32, 0, 3);
        return this.clipSnapshot.edgeClipped(face, chunkAlongEdge);
    }


    private TerrainProfile terrainProfile(LodColumn column) {
        long best = ChunkLodColumns.outerTerrainInterval(column);
        if (best == 0 || this.columnWorldBottom && LodColumn.maxY(best) <= 1) return null;
        return new TerrainProfile(LodColumn.state(best), LodColumn.maxY(best));
    }

    /** Beide Seiten messen eine Aufloesungsnaht, emittieren aber nur ihre eigene Aussenflaeche. */
    static boolean resolutionTransition(int currentLevel, int neighborLevel) {
        return currentLevel != neighborLevel;
    }

    static boolean ownsMaskTransition(boolean currentClipped, boolean neighborClipped) {
        return !currentClipped && neighborClipped;
    }

    private void replaceClippedBoundaryProxies(LodDataSource source, int s, int n) {
        for (int z = 0; z < n; z++) for (int x = 0; x < n; x++) {
            if (!this.clipped[z * n + x]) continue;
            int face = x > 0 && !this.clipped[z * n + x - 1] ? 4
                    : x + 1 < n && !this.clipped[z * n + x + 1] ? 5
                    : z > 0 && !this.clipped[(z - 1) * n + x] ? 2
                    : z + 1 < n && !this.clipped[(z + 1) * n + x] ? 3 : -1;
            if (face < 0) continue;
            int wx = this.regionBaseX + x * s;
            int wz = this.regionBaseZ + z * s;
            int sx = face == 5 ? wx + s - 1 : face == 4 ? wx : wx + (s >> 1);
            int sz = face == 3 ? wz + s - 1 : face == 2 ? wz : wz + (s >> 1);
            this.columns[(z + 1) * this.stride + x + 1] = source.sampleColumn(sx, sz, 1);
        }
    }

    private void replaceRegionHalos(LodDataSource source, LodConfig config, int level,
                                    int s, int n, int ax, int az) {
        for (int face = 2; face <= 5; face++) for (int i = 0; i < n; i++) {
            int neighbor = this.boundaryNeighborLevel(config, face, i, s, ax, az);
            boolean exactNeighbor = this.boundaryNeighborClipped(face, i, s);
            if (!exactNeighbor && !resolutionTransition(level, neighbor)) continue;
            this.replaceRegionHaloCell(source, face, i,
                    exactNeighbor ? 1 : config.cellSize(neighbor), s, n);
        }
    }

    private void replaceRegionHaloCell(LodDataSource source, int face, int i,
                                       int neighborSize, int s, int n) {
        int x = face == 4 ? -1 : face == 5 ? n : i;
        int z = face == 2 ? -1 : face == 3 ? n : i;
        int wx = this.regionBaseX + x * s;
        int wz = this.regionBaseZ + z * s;
        int sx = Math.floorDiv(wx + (s >> 1), neighborSize) * neighborSize;
        int sz = Math.floorDiv(wz + (s >> 1), neighborSize) * neighborSize;
        if (face == 4) sx = this.regionBaseX - neighborSize;
        else if (face == 5) sx = this.regionBaseX + n * s;
        else if (face == 2) sz = this.regionBaseZ - neighborSize;
        else sz = this.regionBaseZ + n * s;
        this.columns[(z + 1) * this.stride + x + 1] = source.sampleColumn(sx, sz, neighborSize);
    }

    /**
     * AO für die vier Ecken einer Spalten-Topfläche. Das bereits bulk-geladene Halo liefert
     * alle drei Nachbarsamples je Ecke; es entstehen weder Einzelzugriffe noch neue Locks.
     */
    private void computeColumnCellAo(int x, int z, float top, float[] out) {
        out[0] = this.columnCornerAo(top, x - 1, z, x, z - 1, x - 1, z - 1); // NW
        out[1] = this.columnCornerAo(top, x - 1, z, x, z + 1, x - 1, z + 1); // SW
        out[2] = this.columnCornerAo(top, x + 1, z, x, z + 1, x + 1, z + 1); // SE
        out[3] = this.columnCornerAo(top, x + 1, z, x, z - 1, x + 1, z - 1); // NE
    }

    private float columnCellAoFlat(int x, int z, float top) {
        this.computeColumnCellAo(x, z, top, this.aoFlatScratch);
        float avg = (this.aoFlatScratch[0] + this.aoFlatScratch[1]
                + this.aoFlatScratch[2] + this.aoFlatScratch[3]) * 0.25F;
        return 0.4F + Math.round((avg - 0.4F) / 0.2F) * 0.2F;
    }

    private boolean columnCellAoUniform(int x, int z, float top, float value) {
        return this.columnCornerAo(top, x - 1, z, x, z - 1, x - 1, z - 1) == value
                && this.columnCornerAo(top, x - 1, z, x, z + 1, x - 1, z + 1) == value
                && this.columnCornerAo(top, x + 1, z, x, z + 1, x + 1, z + 1) == value
                && this.columnCornerAo(top, x + 1, z, x, z - 1, x + 1, z - 1) == value;
    }

    private float columnCornerAo(float top, int edgeXX, int edgeXZ, int edgeZX, int edgeZZ,
                                 int diagonalX, int diagonalZ) {
        boolean edgeX = this.columnOccludesAo(edgeXX, edgeXZ, top);
        boolean edgeZ = this.columnOccludesAo(edgeZX, edgeZZ, top);
        boolean diagonal = this.columnOccludesAo(diagonalX, diagonalZ, top);
        return aoBrightness(edgeX, edgeZ, diagonal);
    }

    /** Prüft die LOD-Blockzelle unmittelbar über der betrachteten Topfläche. */
    private boolean columnOccludesAo(int x, int z, float top) {
        int y = (int) Math.floor(top);
        return this.columnOccludesAoAt(x, z, y);
    }

    private boolean columnOccludesAoAt(int x, int z, int y) {
        LodColumn column = this.column(x, z);
        for (int i = 0; i < column.size(); i++) {
            long interval = column.interval(i);
            if (LodColumn.minY(interval) > y) break;
            if (LodColumn.maxY(interval) > y
                    && this.appearance.occludesAo(LodColumn.state(interval))) return true;
        }
        return false;
    }

    /**
     * Minecraft-artige AO-Signatur einer einzelnen vertikalen LOD-Zellwand. Die vier
     * 2-Bit-Werte folgen der Emissionsreihenfolge (A unten, B unten, B oben, A oben).
     * Sie ist Teil des Greedy-Vergleichs, damit Kontaktverschattung nicht über eine lange
     * Wand hinweg interpoliert wird.
     */
    private int wallCellAo(int face, int x, int z, int bottom, int top, int block) {
        if (!this.columnAo || this.appearance.isTranslucent(block)) return 0xFF;
        int outsideX = x, outsideZ = z;
        if (face == 2) outsideZ--;
        else if (face == 3) outsideZ++;
        else if (face == 4) outsideX--;
        else outsideX++;

        int aDx = 0, aDz = 0;
        if (face == 2) aDx = 1;
        else if (face == 3) aDx = -1;
        else if (face == 4) aDz = -1;
        else aDz = 1;
        int bDx = -aDx, bDz = -aDz;
        int bottomY = bottom;
        int topY = top - 1;
        int a0 = this.wallCornerAoLevel(outsideX, outsideZ, bottomY, aDx, aDz, -1);
        int b0 = this.wallCornerAoLevel(outsideX, outsideZ, bottomY, bDx, bDz, -1);
        int b1 = this.wallCornerAoLevel(outsideX, outsideZ, topY, bDx, bDz, 1);
        int a1 = this.wallCornerAoLevel(outsideX, outsideZ, topY, aDx, aDz, 1);
        return a0 | b0 << 2 | b1 << 4 | a1 << 6;
    }

    private int wallCornerAoLevel(int x, int z, int y, int tangentX, int tangentZ,
                                  int vertical) {
        boolean direct = this.columnOccludesAoAt(x, z, y);
        boolean side1 = this.columnOccludesAoAt(x + tangentX, z + tangentZ, y);
        boolean side2 = this.columnOccludesAoAt(x, z, y + vertical);
        if (side1 && side2) return 0;
        boolean corner = this.columnOccludesAoAt(x + tangentX, z + tangentZ, y + vertical);
        return Math.max(0, 3 - (direct ? 1 : 0) - (side1 ? 1 : 0)
                - (side2 ? 1 : 0) - (corner ? 1 : 0));
    }

    private static float aoFromPacked(int packed, int corner) {
        return 0.4F + ((packed >>> (corner << 1)) & 3) * 0.2F;
    }

    /** AO an den echten Endpunkten eines ggf. greedy-gemergten Wandsegments. */
    private int wallGeometryAo(int face, float xa, float za, float xb, float zb,
                               float bottom, float top) {
        if (!this.columnAo) return 0xFF;
        int outsideA_X, outsideA_Z, outsideB_X, outsideB_Z;
        int tangentA_X = 0, tangentA_Z = 0, tangentB_X = 0, tangentB_Z = 0;
        if (face == 2 || face == 3) {
            int boundaryZ = Math.round(za / this.currentCellSize());
            int outsideZ = face == 2 ? boundaryZ - 1 : boundaryZ;
            int dir = xb > xa ? 1 : -1;
            outsideA_X = dir > 0 ? Math.round(xa / this.currentCellSize())
                    : Math.round(xa / this.currentCellSize()) - 1;
            outsideB_X = dir > 0 ? Math.round(xb / this.currentCellSize()) - 1
                    : Math.round(xb / this.currentCellSize());
            outsideA_Z = outsideB_Z = outsideZ;
            tangentA_X = -dir;
            tangentB_X = dir;
        } else {
            int boundaryX = Math.round(xa / this.currentCellSize());
            int outsideX = face == 4 ? boundaryX - 1 : boundaryX;
            int dir = zb > za ? 1 : -1;
            outsideA_Z = dir > 0 ? Math.round(za / this.currentCellSize())
                    : Math.round(za / this.currentCellSize()) - 1;
            outsideB_Z = dir > 0 ? Math.round(zb / this.currentCellSize()) - 1
                    : Math.round(zb / this.currentCellSize());
            outsideA_X = outsideB_X = outsideX;
            tangentA_Z = -dir;
            tangentB_Z = dir;
        }
        int bottomY = (int) Math.floor(bottom);
        int topY = (int) Math.ceil(top) - 1;
        int a0 = this.wallCornerAoLevel(outsideA_X, outsideA_Z, bottomY,
                tangentA_X, tangentA_Z, -1);
        int b0 = this.wallCornerAoLevel(outsideB_X, outsideB_Z, bottomY,
                tangentB_X, tangentB_Z, -1);
        int b1 = this.wallCornerAoLevel(outsideB_X, outsideB_Z, topY,
                tangentB_X, tangentB_Z, 1);
        int a1 = this.wallCornerAoLevel(outsideA_X, outsideA_Z, topY,
                tangentA_X, tangentA_Z, 1);
        return a0 | b0 << 2 | b1 << 4 | a1 << 6;
    }

    private float currentCellSize() {
        return (float) (this.sizeRegions * REGION_BLOCKS) / this.cellCount;
    }

    private static final class SideSignature {
        /* Im schlechtesten Fall wechselt die AO-Signatur in jeder der 512 Hoehenzellen.
           Die Signatur darf dann keine Flaechen still verwerfen. */
        private static final int CAPACITY = 512;
        final int[] blocks = new int[CAPACITY];
        final int[] minY = new int[CAPACITY];
        final int[] maxY = new int[CAPACITY];
        final long[] lightSurfaces = new long[CAPACITY];
        final int[] ao = new int[CAPACITY];
        int size;

        void clear() { this.size = 0; }
        void add(int block, int minY, int maxY, long lightSurface, int ao) {
            if (this.size >= CAPACITY) return;
            int index = this.size++;
            this.blocks[index] = block;
            this.minY[index] = minY;
            this.maxY[index] = maxY;
            this.lightSurfaces[index] = lightSurface;
            this.ao[index] = ao;
        }
        boolean sameAs(SideSignature other) {
            if (this.size != other.size) return false;
            for (int i = 0; i < this.size; i++) {
                if (this.blocks[i] != other.blocks[i] || this.minY[i] != other.minY[i]
                        || this.maxY[i] != other.maxY[i]
                        || this.lightSurfaces[i] != other.lightSurfaces[i]
                        || this.ao[i] != other.ao[i]) return false;
            }
            return true;
        }
    }

    private final SideSignature sideSignature = new SideSignature();
    private final SideSignature candidateSideSignature = new SideSignature();

    private static boolean packedAoUniform(int packed) {
        int first = packed & 3;
        return ((packed >>> 2) & 3) == first
                && ((packed >>> 4) & 3) == first
                && ((packed >>> 6) & 3) == first;
    }

    /**
     * Zerlegt hohe Seiten an AO-Wechseln. Nur vollstaendig gleichmaessige, identische
     * AO-Zeilen duerfen vertikal zusammengefasst werden; andernfalls wuerde Greedy-Meshing
     * die Kontaktverschattung ueber viele Bloecke interpolieren.
     */
    private void addAoSegmentedSide(SideSignature result, int block, int face, int x, int z,
                                    int minY, int maxY, long lightSurface) {
        if (minY >= maxY) return;
        if (!this.columnAo || this.appearance.isTranslucent(block)) {
            result.add(block, minY, maxY, lightSurface, 0xFF);
            return;
        }
        if (maxY - minY <= 2) {
            result.add(block, minY, maxY, lightSurface,
                    this.wallCellAo(face, x, z, minY, maxY, block));
            return;
        }
        int runMin = minY;
        int runAo = this.wallCellAo(face, x, z, minY, minY + 1, block);
        for (int y = minY + 1; y < maxY; y++) {
            int ao = this.wallCellAo(face, x, z, y, y + 1, block);
            if (ao == runAo && packedAoUniform(ao)) continue;
            result.add(block, runMin, y, lightSurface, runAo);
            runMin = y;
            runAo = ao;
        }
        result.add(block, runMin, maxY, lightSurface, runAo);
    }

    private void visibleSides(SideSignature result, LodColumn own, LodColumn neighbor,
                              boolean transitionEdge, int face, int x, int z) {
        result.clear();
        /* Aufloesungs- und L0-Maskengrenzen werden separat aus den beiden realen Spalten
           aufgebaut. Das regulaere Halo-Meshing darf dieselbe Kante nicht duplizieren. */
        if (transitionEdge) return;
        long lightSurface = this.columnWallLightSurface(own, neighbor);
        for (int i = 0; i < own.size(); i++) {
            long interval = own.interval(i);
            int state = LodColumn.state(interval);
            int start = LodColumn.minY(interval), end = LodColumn.maxY(interval);
            for (int j = 0; j < neighbor.size() && start < end; j++) {
                long cover = neighbor.interval(j);
                int coverMin = LodColumn.minY(cover), coverMax = LodColumn.maxY(cover);
                if (coverMax <= start || coverMin >= end) continue;
                if (!this.appearance.isTranslucent(LodColumn.state(interval))
                        && this.appearance.isTranslucent(LodColumn.state(cover))) continue;
                if (coverMin > start) {
                    int visibleEnd = Math.min(end, coverMin);
                    this.addAoSegmentedSide(result, LodColumn.state(interval), face, x, z,
                            start, visibleEnd, lightSurface);
                }
                start = Math.max(start, coverMax);
            }
            if (start < end) this.addAoSegmentedSide(result, LodColumn.state(interval), face,
                    x, z, start, end, lightSurface);
        }
    }

    private long columnWallLightSurface(LodColumn own, LodColumn neighbor) {
        long best = LodDataSource.pack(Blocks.AIR, 0);
        int top = 0;
        for (int pass = 0; pass < 2; pass++) {
            LodColumn column = pass == 0 ? own : neighbor;
            for (int i = 0; i < column.size(); i++) {
                long interval = column.interval(i);
                int state = LodColumn.state(interval);
                if (!this.appearance.attenuatesSkyLight(state) || LodColumn.maxY(interval) <= top) continue;
                top = LodColumn.maxY(interval);
                best = LodDataSource.pack(state, top - 1);
            }
        }
        return best;
    }

    private void columnWallsAlongX(int z, int dz, int face, int s, int maxRun) {
        boolean regionEdge = z + dz < 0 || z + dz >= this.cellCount;
        for (int x = 0; x < this.cellCount;) {
            if (this.clipped[z * this.cellCount + x]) { x++; continue; }
            boolean maskEdge = !regionEdge && ownsMaskTransition(false,
                    this.neighborClipped(x, z + dz));
            boolean transitionEdge = maskEdge || regionEdge
                    && this.transitionEdges[(face - 2) * this.cellCount + x];
            this.visibleSides(this.sideSignature, this.column(x, z), this.column(x, z + dz),
                    transitionEdge, face, x, z);
            if (this.sideSignature.size == 0) { x++; continue; }
            int run = 1;
            while (x + run < this.cellCount && run < maxRun
                    && !this.clipped[z * this.cellCount + x + run]
                    && (!regionEdge && this.neighborClipped(x + run, z + dz)) == maskEdge
                    && (!regionEdge || this.transitionEdges[(face - 2) * this.cellCount + x + run]
                    == transitionEdge)) {
                this.visibleSides(this.candidateSideSignature, this.column(x + run, z),
                        this.column(x + run, z + dz), transitionEdge,
                        face, x + run, z);
                if (!this.sideSignature.sameAs(this.candidateSideSignature)) break;
                run++;
            }
            float x0 = x * s, x1 = (x + run) * s, zz = (dz < 0 ? z : z + 1) * s;
            for (int i = 0; i < this.sideSignature.size; i++) {
                if (face == 2) this.emitWall(this.sideSignature.blocks[i], face, x1, zz, x0, zz,
                        this.sideSignature.minY[i], this.sideSignature.maxY[i],
                        this.sideSignature.lightSurfaces[i], this.sideSignature.ao[i]);
                else this.emitWall(this.sideSignature.blocks[i], face, x0, zz, x1, zz,
                        this.sideSignature.minY[i], this.sideSignature.maxY[i],
                        this.sideSignature.lightSurfaces[i], this.sideSignature.ao[i]);
            }
            x += run;
        }
    }

    private void columnWallsAlongZ(int x, int dx, int face, int s, int maxRun) {
        boolean regionEdge = x + dx < 0 || x + dx >= this.cellCount;
        for (int z = 0; z < this.cellCount;) {
            if (this.clipped[z * this.cellCount + x]) { z++; continue; }
            boolean maskEdge = !regionEdge && ownsMaskTransition(false,
                    this.neighborClipped(x + dx, z));
            boolean transitionEdge = maskEdge || regionEdge
                    && this.transitionEdges[(face - 2) * this.cellCount + z];
            this.visibleSides(this.sideSignature, this.column(x, z), this.column(x + dx, z),
                    transitionEdge, face, x, z);
            if (this.sideSignature.size == 0) { z++; continue; }
            int run = 1;
            while (z + run < this.cellCount && run < maxRun
                    && !this.clipped[(z + run) * this.cellCount + x]
                    && (!regionEdge && this.neighborClipped(x + dx, z + run)) == maskEdge
                    && (!regionEdge || this.transitionEdges[(face - 2) * this.cellCount + z + run]
                    == transitionEdge)) {
                this.visibleSides(this.candidateSideSignature, this.column(x, z + run),
                        this.column(x + dx, z + run), transitionEdge,
                        face, x, z + run);
                if (!this.sideSignature.sameAs(this.candidateSideSignature)) break;
                run++;
            }
            float z0 = z * s, z1 = (z + run) * s, xx = (dx < 0 ? x : x + 1) * s;
            for (int i = 0; i < this.sideSignature.size; i++) {
                if (face == 4) this.emitWall(this.sideSignature.blocks[i], face, xx, z0, xx, z1,
                        this.sideSignature.minY[i], this.sideSignature.maxY[i],
                        this.sideSignature.lightSurfaces[i], this.sideSignature.ao[i]);
                else this.emitWall(this.sideSignature.blocks[i], face, xx, z1, xx, z0,
                        this.sideSignature.minY[i], this.sideSignature.maxY[i],
                        this.sideSignature.lightSurfaces[i], this.sideSignature.ao[i]);
            }
            z += run;
        }
    }

    /**
     * Schließt Auflösungsgrenzen mit der tatsächlichen feineren Randkontur. Terrain wird nur
     * zwischen zwei validen äußeren Höhen verbunden; Fluide und Landmarken werden separat
     * verglichen. So kann eine leere oder beschädigte Feinspalte keine Tiefenwand erzeugen.
     */
    private void columnMeasuredTransitions(LodDataSource source, LodConfig config, int level,
                                           int s, int n, int ax, int az) {
        for (int z = 0; z < n; z++) for (int x = 0; x < n; x++) {
            if (this.clipped[z * n + x]) continue;
            if (x > 0 && ownsMaskTransition(false, this.clipped[z * n + x - 1])) {
                this.emitMeasuredTransition(source, this.column(x, z), 4,
                        x * s, z * s, s, 1, level, 0);
            }
            if (x + 1 < n && ownsMaskTransition(false, this.clipped[z * n + x + 1])) {
                this.emitMeasuredTransition(source, this.column(x, z), 5,
                        (x + 1) * s, z * s, s, 1, level, 0);
            }
            if (z > 0 && ownsMaskTransition(false, this.clipped[(z - 1) * n + x])) {
                this.emitMeasuredTransition(source, this.column(x, z), 2,
                        z * s, x * s, s, 1, level, 0);
            }
            if (z + 1 < n && ownsMaskTransition(false, this.clipped[(z + 1) * n + x])) {
                this.emitMeasuredTransition(source, this.column(x, z), 3,
                        (z + 1) * s, x * s, s, 1, level, 0);
            }
        }

        for (int face = 2; face <= 5; face++) for (int i = 0; i < n; i++) {
            int neighbor = this.boundaryNeighborLevel(config, face, i, s, ax, az);
            boolean exactNeighbor = this.boundaryNeighborClipped(face, i, s);
            if (!exactNeighbor && !resolutionTransition(level, neighbor)) continue;
            int x = face == 4 ? 0 : face == 5 ? n - 1 : i;
            int z = face == 2 ? 0 : face == 3 ? n - 1 : i;
            if (this.clipped[z * n + x]) continue;
            int boundary = face == 4 || face == 2 ? 0 : n * s;
            this.emitMeasuredTransition(source, this.column(x, z), face,
                    boundary, i * s, s, exactNeighbor ? 1 : config.cellSize(neighbor),
                    level, exactNeighbor ? 0 : neighbor);
        }
    }

    /** boundary = konstante lokale x/z-Koordinate, tangent = Start entlang der Kante. */
    private void emitMeasuredTransition(LodDataSource source, LodColumn ownColumn, int face,
                                        int boundary, int tangent, int length, int neighborSize,
                                        int ownLevel, int neighborLevel) {
        if (neighborSize <= 0) return;
        int ownSize = Math.round(this.currentCellSize());
        int segmentSize = Math.min(ownSize, neighborSize);
        int ownX = face == 4 ? boundary / ownSize
                : face == 5 ? boundary / ownSize - 1
                : tangent / ownSize;
        int ownZ = face == 2 ? boundary / ownSize
                : face == 3 ? boundary / ownSize - 1
                : tangent / ownSize;
        ownX = Math.clamp(ownX, 0, this.cellCount - 1);
        ownZ = Math.clamp(ownZ, 0, this.cellCount - 1);
        for (int offset = 0; offset < length; offset += segmentSize) {
            int segment = Math.min(segmentSize, length - offset);
            int worldBoundary = (face == 4 || face == 5 ? this.regionBaseX : this.regionBaseZ)
                    + boundary;
            int worldTangent = (face == 4 || face == 5 ? this.regionBaseZ : this.regionBaseX)
                    + tangent + offset;
            int sampleX, sampleZ;
            if (face == 4 || face == 5) {
                sampleX = face == 4 ? worldBoundary - neighborSize : worldBoundary;
                sampleZ = Math.floorDiv(worldTangent, neighborSize) * neighborSize;
            } else {
                sampleX = Math.floorDiv(worldTangent, neighborSize) * neighborSize;
                sampleZ = face == 2 ? worldBoundary - neighborSize : worldBoundary;
            }
            LodColumn neighborColumn = source.sampleColumn(sampleX, sampleZ, neighborSize);
            int t0 = tangent + offset, t1 = t0 + segment;
            TerrainProfile ownTerrain = this.terrainProfile(ownColumn);
            TerrainProfile neighborTerrain = this.terrainProfile(neighborColumn);
            if (ownTerrain != null && neighborTerrain != null) {
                if (this.stats != null) {
                    int transitionX = face == 4 || face == 5
                            ? worldBoundary : worldTangent + (segment >> 1);
                    int transitionZ = face == 2 || face == 3
                            ? worldBoundary : worldTangent + (segment >> 1);
                    this.stats.recordTransition(transitionX, transitionZ, face,
                            ownLevel, neighborLevel, ownSize, neighborSize,
                            ownTerrain.top, neighborTerrain.top);
                }
                /* Jede Region besitzt nur die wirklich exponierten Intervalle ihrer eigenen
                   Spalte. Der Nachbar emittiert seine Gegenrichtung selbst. Dadurch gibt es
                   weder doppelseitige Blind-Skirts noch fremde Oberflaechenmaterialien. */
                this.visibleSides(this.sideSignature, ownColumn, neighborColumn,
                        false, face, ownX, ownZ);
                for (int i = 0; i < this.sideSignature.size; i++) {
                    this.emitTransitionWall(this.sideSignature.blocks[i], face,
                            boundary, t0, t1, this.sideSignature.minY[i],
                            this.sideSignature.maxY[i], this.sideSignature.lightSurfaces[i]);
                }
            } else {
                if (this.stats != null) this.stats.transitionProfilesMissing++;
                /* Generatorwelten garantieren eine Terrainhülle. Ist eine Seite dennoch
                   unbrauchbar, schließt ein genau eine feine Zelle breiter Deckel lokal die
                   Naht. Anders als ein Skirt kann er niemals bis zum Weltboden ausufern.
                   Importwelten dürfen dagegen echte leere Spalten enthalten. */
                if (this.columnWorldBottom && ownTerrain != null) {
                    this.emitTransitionSafetyCap(ownTerrain, face, boundary, t0, t1,
                            segmentSize, true);
                } else if (this.columnWorldBottom && neighborTerrain != null) {
                    this.emitTransitionSafetyCap(neighborTerrain, face, boundary, t0, t1,
                            segmentSize, false);
                }
            }
        }
    }

    /**
     * Horizontaler Fehlerabschluss auf der fehlenden Seite einer Naht. {@code neighborSide}
     * wählt die Seite außerhalb der groben Zelle; andernfalls wird die grobe Seite bedeckt.
     */
    private void emitTransitionSafetyCap(TerrainProfile profile, int face, int boundary,
                                         int t0, int t1, int fineSize,
                                         boolean neighborSide) {
        float x0, x1, z0, z1;
        if (face == 4 || face == 5) {
            boolean lower = face == 4 ? neighborSide : !neighborSide;
            x0 = lower ? boundary - fineSize : boundary;
            x1 = lower ? boundary : boundary + fineSize;
            z0 = t0;
            z1 = t1;
        } else {
            boolean lower = face == 2 ? neighborSide : !neighborSide;
            x0 = t0;
            x1 = t1;
            z0 = lower ? boundary - fineSize : boundary;
            z1 = lower ? boundary : boundary + fineSize;
        }
        this.emitTop(profile.state, AO_NONE, x0, z0, x1, z1, profile.top, 15);
        if (this.stats != null) this.stats.transitionSafetyCaps++;
    }

    /** Emittiert ausschließlich Detailintervalle; Terrain läuft über validierte Außenprofile. */
    private void emitTransitionWall(int block, int face, int boundary, int t0, int t1,
                                    int minY, int maxY, long lightSurface) {
        if (face == 2) this.emitAoSegmentedWall(block, face, t1, boundary, t0, boundary,
                minY, maxY, lightSurface);
        else if (face == 3) this.emitAoSegmentedWall(block, face, t0, boundary, t1, boundary,
                minY, maxY, lightSurface);
        else if (face == 4) this.emitAoSegmentedWall(block, face, boundary, t0, boundary, t1,
                minY, maxY, lightSurface);
        else this.emitAoSegmentedWall(block, face, boundary, t1, boundary, t0,
                minY, maxY, lightSurface);
    }

    private void emitAoSegmentedWall(int block, int face, float xa, float za,
                                     float xb, float zb, int minY, int maxY,
                                     long lightSurface) {
        if (minY >= maxY) return;
        if (!this.columnAo || this.appearance.isTranslucent(block)) {
            this.emitWall(block, face, xa, za, xb, zb, minY, maxY, lightSurface, 0xFF);
            return;
        }
        if (maxY - minY <= 2) {
            int ao = this.wallGeometryAo(face, xa, za, xb, zb, minY, maxY);
            this.emitWall(block, face, xa, za, xb, zb, minY, maxY, lightSurface, ao);
            return;
        }
        int runMin = minY;
        int runAo = this.wallGeometryAo(face, xa, za, xb, zb, minY, minY + 1);
        for (int y = minY + 1; y < maxY; y++) {
            int ao = this.wallGeometryAo(face, xa, za, xb, zb, y, y + 1);
            if (ao == runAo && packedAoUniform(ao)) continue;
            this.emitWall(block, face, xa, za, xb, zb, runMin, y, lightSurface, runAo);
            runMin = y;
            runAo = ao;
        }
        this.emitWall(block, face, xa, za, xb, zb, runMin, maxY, lightSurface, runAo);
    }

    /**
     * Positions-Skala der Vertex-Packung je Regionsgröße: 128er packen mit 1/256,
     * Superregionen mit 1/64 — der u16-Fixed-Point trägt bei 1/256 nur ~255
     * Blöcke Region-lokale Spanne, bei 1/64 ~1023 (x/z UND y). Muss exakt zum per-Draw
     * .w-Wert des Renderers passen ({@code LodMesh.invPosScale}).
     *
     * <p>Die 256 ist hier bewusst eine EIGENE Konstante und nicht mehr {@code ChunkMesher.POS_SCALE}:
     * Sections brauchen Auflösung (dort inzwischen 1/1024), LOD-Regionen brauchen Reichweite. Mit
     * 1/1024 käme eine 128er-Region nur noch ~63 Blöcke weit und würde in {@code fixedPos} still
     * auf 0xFFFF klemmen.
     */
    public static float posScaleFor(int sizeRegions) {
        /* Mehrschichtige Spalten können gleichzeitig Weltboden und hohe Landmarken tragen.
           1/128 deckt 0..512 vollständig ab und behält 1/128-Block-Präzision; X/Z einer
           128er-Region benötigen nur die halbe Reichweite. */
        return sizeRegions > 1 ? 64F : 127F;
    }

    /* ------------------------- Sampling ------------------------- */

    /**
     * Sample einer Zelle mit Ursprung (wx,wz): innerhalb des eigenen Footprints
     * ([rx, rx+size) × [rz, rz+size) in 128er-Regionskoordinaten) aufs eigene Raster (s);
     * sonst an der Rasterposition des NACHBAR-Levels gesampelt (s2-ausgerichtet), aber im
     * EIGENEN Raster quantisiert. Die Ring-Höhen fließen ausschließlich in VERGLEICHE mit
     * eigenen Zellen ein (Wand-Bedingung, Ecken-AO) — Vergleichbarkeit schlägt hier
     * Mesh-Exaktheit: mit Nachbar-Quantisierung (s2) läge flaches Terrain an Level-Grenzen
     * scheinbar auf zwei Höhen (floor auf verschiedene Vielfache) → Phantom-AO-Streifen und
     * Phantom-Stufenwände entlang der Randzellreihe. Der reale Restversatz der gerenderten
     * Meshes (< MAX_QUANT_STRIDE) bleibt und wird von den Regionsrand-Skirts (>= 32 Blöcke)
     * verdeckt. Innerhalb einer Superregion mit uniformem Level ist das Sample identisch zu
     * dem der ungemergten 128er-Regionen (s teilt 128, Zellursprünge auf demselben globalen
     * Raster) — Determinismus an diesen Nähten bleibt erhalten.
     */
    private long sampleCell(LodDataSource source, LodConfig config, int wx, int wz, int s,
                            int rx, int rz, int size, int ax, int az) {
        int rxc = Math.floorDiv(wx, REGION_BLOCKS);
        int rzc = Math.floorDiv(wz, REGION_BLOCKS);
        if (rxc >= rx && rxc < rx + size && rzc >= rz && rzc < rz + size) {
            return this.quantizeHeight(source.sampleSurface(wx, wz, s), s);
        }

        int s2 = config.cellSize(neighborLevel(config, rxc, rzc, ax, az));
        return this.quantizeHeight(
                source.sampleSurface(Math.floorDiv(wx, s2) * s2, Math.floorDiv(wz, s2) * s2, s2), s);
    }

    /** Boden-Variante von {@link #sampleCell} — gleiche Rasterlogik, liefert den festen Grund. */
    private long sampleGroundCell(LodDataSource source, LodConfig config, int wx, int wz, int s,
                                  int rx, int rz, int size, int ax, int az) {
        int rxc = Math.floorDiv(wx, REGION_BLOCKS);
        int rzc = Math.floorDiv(wz, REGION_BLOCKS);
        if (rxc >= rx && rxc < rx + size && rzc >= rz && rzc < rz + size) {
            return this.quantizeHeight(source.sampleGround(wx, wz, s), s);
        }

        int s2 = config.cellSize(neighborLevel(config, rxc, rzc, ax, az));
        return this.quantizeHeight(
                source.sampleGround(Math.floorDiv(wx, s2) * s2, Math.floorDiv(wz, s2) * s2, s2), s);
    }

    /**
     * Rundet die Terrain-Höhe eines Samples auf ein Vielfaches der Zell-Stride ab
     * ({@code floor(h/q)*q} mit {@code q = min(stride, MAX_QUANT_STRIDE)}) — Nachbarzellen teilen
     * häufiger dieselbe Höhe, sodass der Greedy-Merge greift statt zellbreiter 1-Block-Stufen.
     * Der Deckel hält die Stufenhöhe optisch verträglich. {@code floor} hält LOD-Terrain unter
     * der realen Oberfläche (keine überstehenden Klötze an der L0-Naht). Rein aus (Höhe, Stride)
     * abgeleitet, also deterministisch. Wird auch für Rand-Ring-Zellen fremder Regionen mit der
     * EIGENEN Stride {@code s} aufgerufen (s. {@link #sampleCell}) — Ring-Höhen dienen nur dem
     * Vergleich mit eigenen Zellen, nicht der Rekonstruktion des Nachbar-Meshes.
     * Wasser bleibt roh (Spiegel flach/koplanar), {@code stride <= 1} = kein Effekt.
     */
    private long quantizeHeight(long sample, int stride) {
        if (stride <= 1) return sample;
        int block = LodDataSource.block(sample);
        if (this.appearance.isFluid(block)) return sample;
        int q = Math.min(stride, MAX_QUANT_STRIDE);
        int h = LodDataSource.height(sample);
        return LodDataSource.pack(block, Math.floorDiv(h, q) * q);
    }

    private static int neighborLevel(LodConfig config, int nrx, int nrz, int ax, int az) {
        double dx = (nrx + 0.5) * REGION_BLOCKS - ax;
        double dz = (nrz + 0.5) * REGION_BLOCKS - az;
        return config.levelAt(Math.sqrt(dx * dx + dz * dz));
    }

    private static LodNeighborSnapshot neighborSnapshotFromAnchor(LodConfig config,
                                                                  int rx, int rz,
                                                                  int ax, int az) {
        return new LodNeighborSnapshot(
                neighborLevel(config, rx, rz - 1, ax, az),
                neighborLevel(config, rx, rz + 1, ax, az),
                neighborLevel(config, rx - 1, rz, ax, az),
                neighborLevel(config, rx + 1, rz, ax, az));
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
        if (cx < 0) return this.boundaryNeighborClipped(4, Math.clamp(cz, 0, n - 1),
                Math.round(this.currentCellSize()));
        if (cx >= n) return this.boundaryNeighborClipped(5, Math.clamp(cz, 0, n - 1),
                Math.round(this.currentCellSize()));
        if (cz < 0) return this.boundaryNeighborClipped(2, Math.clamp(cx, 0, n - 1),
                Math.round(this.currentCellSize()));
        if (cz >= n) return this.boundaryNeighborClipped(3, Math.clamp(cx, 0, n - 1),
                Math.round(this.currentCellSize()));
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

    /**
     * Analytische Himmelslicht-Näherung für LOD-Geometrie: freie Oberflächen bleiben bei 15,
     * unter einer Fluid-Oberfläche kostet jeder volle Block Tiefe eine Lichtstufe. Das bildet
     * insbesondere den sichtbaren Meeresboden ab, ohne Lichtdaten für Fernregionen zu erzeugen.
     */
    private int skyLightAt(long surfaceSample, float y) {
        if (!this.appearance.attenuatesSkyLight(LodDataSource.block(surfaceSample))) return 15;
        int depth = Math.max(0, (int) Math.ceil(this.topOf(surfaceSample) - y));
        return Math.clamp(15 - depth, 0, 15);
    }

    /** Wasseroberfläche, die eine Terrain-Wand von einer ihrer beiden Seiten überdeckt. */
    private long wallLightSurface(long ownSurface, long neighborSurface) {
        boolean ownWater = this.appearance.attenuatesSkyLight(LodDataSource.block(ownSurface));
        boolean neighborWater = this.appearance.attenuatesSkyLight(LodDataSource.block(neighborSurface));
        if (!ownWater) return neighborWater ? neighborSurface : ownSurface;
        if (!neighborWater) return ownSurface;
        return this.topOf(ownSurface) >= this.topOf(neighborSurface) ? ownSurface : neighborSurface;
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
            /* Leere Spalte (Void): keine Wand, kein Skirt — auch nicht am Regionsrand. */
            if (LodDataSource.block(sample) == Blocks.AIR) {
                cx++;
                continue;
            }
            long nSample = this.groundCell(cx, cz + dz);
            float top = this.topOf(sample);
            float nTop = this.topOf(nSample);
            /* An Regions- und Maskenrändern IMMER ein tiefer Skirt: Zwei gleiche LOD-Samples
               beweisen nicht, dass die echte L0-Randspalte dieselbe Höhe besitzt. */
            boolean nClipped = this.neighborClipped(cx, cz + dz);
            if (!edge && !nClipped && nTop >= top) {
                cx++;
                continue;
            }
            long lightSurface = this.wallLightSurface(this.cell(cx, cz), this.cell(cx, cz + dz));
            int run = 1;
            while (cx + run < n && run < maxRun && !this.clipped[cz * n + cx + run]
                    && this.groundCell(cx + run, cz) == sample && this.groundCell(cx + run, cz + dz) == nSample
                    && this.wallLightSurface(this.cell(cx + run, cz),
                            this.cell(cx + run, cz + dz)) == lightSurface
                    && this.neighborClipped(cx + run, cz + dz) == nClipped) run++;

            if (this.stats != null) this.recordTerrainWall(edge, nClipped, nTop, top);
            float skirtDepth = edge || nClipped ? this.edgeSkirt : 0F;
            float bottom = Math.max(0F, Math.min(nTop, top) - skirtDepth);
            float x0 = cx * s, x1 = (cx + run) * s;
            float z = (dz < 0 ? cz : cz + 1) * s;
            int block = LodDataSource.block(sample);
            if (face == 2) {
                this.emitWall(block, face, x1, z, x0, z, bottom, top, lightSurface);
            } else {
                this.emitWall(block, face, x0, z, x1, z, bottom, top, lightSurface);
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
            /* Leere Spalte (Void): keine Wand, kein Skirt — auch nicht am Regionsrand. */
            if (LodDataSource.block(sample) == Blocks.AIR) {
                cz++;
                continue;
            }
            long nSample = this.groundCell(cx + dx, cz);
            float top = this.topOf(sample);
            float nTop = this.topOf(nSample);
            /* An Regions- und Maskenrändern IMMER ein tiefer Skirt: Zwei gleiche LOD-Samples
               beweisen nicht, dass die echte L0-Randspalte dieselbe Höhe besitzt. */
            boolean nClipped = this.neighborClipped(cx + dx, cz);
            if (!edge && !nClipped && nTop >= top) {
                cz++;
                continue;
            }
            long lightSurface = this.wallLightSurface(this.cell(cx, cz), this.cell(cx + dx, cz));
            int run = 1;
            while (cz + run < n && run < maxRun && !this.clipped[(cz + run) * n + cx]
                    && this.groundCell(cx, cz + run) == sample && this.groundCell(cx + dx, cz + run) == nSample
                    && this.wallLightSurface(this.cell(cx, cz + run),
                            this.cell(cx + dx, cz + run)) == lightSurface
                    && this.neighborClipped(cx + dx, cz + run) == nClipped) run++;

            if (this.stats != null) this.recordTerrainWall(edge, nClipped, nTop, top);
            float skirtDepth = edge || nClipped ? this.edgeSkirt : 0F;
            float bottom = Math.max(0F, Math.min(nTop, top) - skirtDepth);
            float z0 = cz * s, z1 = (cz + run) * s;
            float x = (dx < 0 ? cx : cx + 1) * s;
            int block = LodDataSource.block(sample);
            if (face == 4) {
                this.emitWall(block, face, x, z0, x, z1, bottom, top, lightSurface);
            } else {
                this.emitWall(block, face, x, z1, x, z0, bottom, top, lightSurface);
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
                this.emitWall(block, face, x1, z, x0, z, nTop, top, sample);
            } else {
                this.emitWall(block, face, x0, z, x1, z, nTop, top, sample);
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
                this.emitWall(block, face, x, z0, x, z1, nTop, top, sample);
            } else {
                this.emitWall(block, face, x, z1, x, z0, nTop, top, sample);
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
    private void emitTop(int block, float[] ao, float x0, float z0, float x1, float z1,
                         float y, int skyLight) {
        int layer = this.appearance.topLayer(block);
        if (layer < 0) return; // Block ohne gebackenes Quad (z.B. Luft) — nichts zu zeichnen
        int tint = this.tintFor(this.appearance.topTint(block), this.appearance.topTintType(block),
                (x0 + x1) * 0.5F, (z0 + z1) * 0.5F);
        float brightness = BlockModels.FACE_BRIGHTNESS[0];
        float u = x1 - x0, v = z1 - z0;
        /* Fluid-Tops gehen in den Translucent-Puffer (transparente Wasserfläche); alles
           andere bleibt opak. Wände an Fluid-Zellen sind analog transluzent (s. emitWall). */
        boolean fluidTop = this.appearance.isTranslucent(block);
        float renderY = fluidTop ? y - FluidGeometry.TOP_RENDER_EPSILON : y;
        if (this.stats != null) {
            if (fluidTop) this.stats.topWater++; else this.stats.topTerrain++;
        }

        this.ensureCapacity(fluidTop);
        this.putVertex(fluidTop, x0, renderY, z0, 0F, 0F, layer, brightness * ao[0], tint, skyLight);
        this.putVertex(fluidTop, x0, renderY, z1, 0F, v, layer, brightness * ao[1], tint, skyLight);
        this.putVertex(fluidTop, x1, renderY, z1, u, v, layer, brightness * ao[2], tint, skyLight);
        this.putVertex(fluidTop, x1, renderY, z0, u, 0F, layer, brightness * ao[3], tint, skyLight);

        if (fluidTop) {
            /* LOD-Wasseroberflächen wie die Nahgeometrie gezielt doppelseitig backen. */
            this.ensureCapacity(true);
            this.putVertex(true, x1, renderY, z0, u, 0F, layer, brightness * ao[3], tint, skyLight);
            this.putVertex(true, x1, renderY, z1, u, v, layer, brightness * ao[2], tint, skyLight);
            this.putVertex(true, x0, renderY, z1, 0F, v, layer, brightness * ao[1], tint, skyLight);
            this.putVertex(true, x0, renderY, z0, 0F, 0F, layer, brightness * ao[0], tint, skyLight);
        }

        if (renderY > this.maxTop) this.maxTop = renderY;
        if (renderY < this.minBottom) this.minBottom = renderY;
    }

    /** Unterseite eines freiliegenden Intervalls, mit derselben 2D-Greedy-Fläche wie das Top. */
    private void emitBottom(int block, float x0, float z0, float x1, float z1,
                            float y, int skyLight) {
        int layer = this.appearance.sideLayer(block);
        if (layer < 0) return;
        int tint = this.tintFor(this.appearance.sideTint(block), this.appearance.sideTintType(block),
                (x0 + x1) * 0.5F, (z0 + z1) * 0.5F);
        float brightness = BlockModels.FACE_BRIGHTNESS[1];
        float u = x1 - x0, v = z1 - z0;
        boolean translucent = this.appearance.isTranslucent(block);
        if (this.stats != null) this.stats.bottom++;
        this.ensureCapacity(translucent);
        this.putVertex(translucent, x1, y, z0, u, 0F, layer, brightness, tint, skyLight);
        this.putVertex(translucent, x1, y, z1, u, v, layer, brightness, tint, skyLight);
        this.putVertex(translucent, x0, y, z1, 0F, v, layer, brightness, tint, skyLight);
        this.putVertex(translucent, x0, y, z0, 0F, 0F, layer, brightness, tint, skyLight);
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

    /** Merge-Kriterium je nach Modus: Fern-Level vergleichen den abgeflachten Zell-Wert. */
    private boolean aoMergeable(int cx, int cz, float ownTop, float value) {
        return this.flatAo ? this.cellAoFlat(cx, cz, ownTop) == value
                : this.cellAoUniform(cx, cz, ownTop, value);
    }

    /**
     * Zell-AO als EIN uniformer Wert: Mittel der 4 Ecken, auf die AO-Leiter (0.4 + k·0.2)
     * gerastet. Identischer Ausdruck für Run-Start und Merge-Kandidaten → bitgleiche Floats,
     * die ==-Vergleiche sind deterministisch.
     */
    private float cellAoFlat(int cx, int cz, float ownTop) {
        this.computeCellAo(cx, cz, ownTop, this.aoFlatScratch);
        float avg = (this.aoFlatScratch[0] + this.aoFlatScratch[1]
                + this.aoFlatScratch[2] + this.aoFlatScratch[3]) * 0.25F;
        return 0.4F + Math.round((avg - 0.4F) / 0.2F) * 0.2F;
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
        return aoBrightness(edgeX, edgeZ, diag);
    }

    /** Minecraft-Regel: zwei belegte Seiten schließen die Ecke unabhängig von der Diagonale. */
    private static float aoBrightness(boolean edgeX, boolean edgeZ, boolean diagonal) {
        int level = edgeX && edgeZ ? 0
                : 3 - (edgeX ? 1 : 0) - (edgeZ ? 1 : 0) - (diagonal ? 1 : 0);
        return 0.4F + level * 0.2F;
    }

    /* ------------------------- Debug-Statistik (nur bei gesetztem stats) ------------------------- */

    /**
     * Zählt die Merge-Grenzen des Terrain-Top-Rasters: für jede interne +x-/+z-Adjazenz der
     * Regionszellen [0,n)² wird GENAU EINMAL bestimmt, warum die beiden Zellen NICHT ins selbe
     * Greedy-Quad dürfen (Material > Höhe > AO, sonst mergebar). Ordnungsunabhängig — spiegelt
     * das Merge-POTENZIAL, nicht die konkrete Greedy-Reihenfolge (s. {@link LodMeshStats}).
     * Der AO-Test bildet die Merge-Regel des Top-Passes ab: zwei gleich-gesampelte Zellen mergen
     * nur, wenn BEIDE AO-uniform sind UND denselben Eckwert tragen.
     */
    private void recordSeams(int n, boolean useAo) {
        /* AO-Uniform-Wert je Zelle vorberechnen (NaN = nicht uniform oder geclippt). Nur offline
           (stats != null) → lokale Allokation ist unkritisch. */
        float[] aoU = null;
        if (useAo) {
            aoU = new float[n * n];
            float[] tmp = new float[4];
            for (int cz = 0; cz < n; cz++) {
                for (int cx = 0; cx < n; cx++) {
                    if (this.clipped[cz * n + cx]) {
                        aoU[cz * n + cx] = Float.NaN;
                        continue;
                    }
                    float top = this.topOf(this.groundCell(cx, cz));
                    if (this.flatAo) { // Statistik spiegelt die Fern-Level-Abflachung
                        aoU[cz * n + cx] = this.cellAoFlat(cx, cz, top);
                        continue;
                    }
                    this.computeCellAo(cx, cz, top, tmp);
                    aoU[cz * n + cx] = (tmp[0] == tmp[1] && tmp[1] == tmp[2] && tmp[2] == tmp[3])
                            ? tmp[0] : Float.NaN;
                }
            }
        }
        for (int cz = 0; cz < n; cz++) {
            for (int cx = 0; cx < n; cx++) {
                if (cx + 1 < n) this.classifySeam(cx, cz, cx + 1, cz, n, useAo, aoU);
                if (cz + 1 < n) this.classifySeam(cx, cz, cx, cz + 1, n, useAo, aoU);
            }
        }
    }

    private void classifySeam(int ax, int az, int bx, int bz, int n, boolean useAo, float[] aoU) {
        int ia = az * n + ax, ib = bz * n + bx;
        if (this.clipped[ia] || this.clipped[ib]) {
            this.stats.seamClipped++;
            return;
        }
        long ga = this.groundCell(ax, az), gb = this.groundCell(bx, bz);
        if (LodDataSource.block(ga) != LodDataSource.block(gb)) {
            this.stats.seamMaterial++;
            return;
        }
        if (LodDataSource.height(ga) != LodDataSource.height(gb)) {
            this.stats.seamHeight++;
            return;
        }
        float top = this.topOf(ga);
        if (this.skyLightAt(this.cell(ax, az), top) != this.skyLightAt(this.cell(bx, bz), top)) {
            this.stats.seamLight++;
            return;
        }
        if (useAo) {
            float va = aoU[ia], vb = aoU[ib];
            if (Float.isNaN(va) || Float.isNaN(vb) || va != vb) {
                this.stats.seamAo++;
                return;
            }
        }
        this.stats.seamMergeable++;
    }

    /**
     * Klassifiziert eine gerade emittierte opake Terrain-Wand: existierte sie auch OHNE
     * Skirt-Regel (echte Reliefstufe, Nachbar niedriger sichtbar → {@code nTop < top})? Wenn
     * nicht, ist es ein reiner Skirt-Quad — am Regionsrand ({@code edge}) oder an einer
     * Masken-Kante ({@code nClipped}). Summe der drei Klassen == {@code wallTerrain}.
     */
    private void recordTerrainWall(boolean edge, boolean nClipped, float nTop, float top) {
        if (nTop < top) this.stats.wallRealStep++;
        else if (edge) this.stats.wallEdgeSkirt++;
        else this.stats.wallMaskSkirt++;
    }

    /**
     * Senkrechte Wand von bottom bis top (absolut) zwischen den Bodenpunkten A=(xa,za) und
     * B=(xb,zb) (A→B = u-Richtung; Aufrufer wählt die CCW-Sicht von außen). v=0 an der
     * Oberkante (Textur-Oben = Face-Oben, wie BlockModels).
     */
    private void emitWall(int block, int face, float xa, float za, float xb, float zb,
                          float bottom, float top, long surfaceSample) {
        this.emitWall(block, face, xa, za, xb, zb, bottom, top, surfaceSample, -1);
    }

    private void emitWall(int block, int face, float xa, float za, float xb, float zb,
                          float bottom, float top, long surfaceSample, int packedAo) {
        if (this.appearance.sideLayer(block) < 0) return; // Block ohne gebackenes Quad
        /* Der UV-Fixed-Point trägt nur ~63 Blöcke v-Spanne — höhere Wände (Klippen,
           Import-Rand über dem Void) in vertikale Segmente teilen, damit die Textur pro
           Block repeatet statt über die volle Höhe gestreckt zu werden. Segmentgrenzen
           liegen ganzzahlige Vielfache unter top → die Texturphase läuft nahtlos durch. */
        float segTop = top;
        while (segTop > bottom) {
            float segBottom = Math.max(bottom, segTop - MAX_MERGE_BLOCKS);
            this.emitWallSegment(block, face, xa, za, xb, zb, segBottom, segTop,
                    surfaceSample, packedAo);
            segTop = segBottom;
        }
    }

    private void emitWallSegment(int block, int face, float xa, float za, float xb, float zb,
                                 float bottom, float top, long surfaceSample, int packedAo) {
        int layer = this.appearance.sideLayer(block);
        int tint = this.tintFor(this.appearance.sideTint(block), this.appearance.sideTintType(block),
                (xa + xb) * 0.5F, (za + zb) * 0.5F);
        float brightness = BlockModels.FACE_BRIGHTNESS[face];
        float u = Math.abs(xb - xa) + Math.abs(zb - za);
        float v = Math.min(top - bottom, MAX_MERGE_BLOCKS);
        int bottomSkyLight = this.skyLightAt(surfaceSample, bottom);
        int topSkyLight = this.skyLightAt(surfaceSample, top);

        /* Wände an Fluid-Zellen sind die sichtbare Seite eines Wasserkörpers (z.B. Seeufer-
           Abbruch) und daher ebenfalls transluzent — sonst dominieren sie aus Nähe/niedriger
           Position (nah an oder unter einer LOD-Wasserkante) den Bildschirm als große opake
           Fläche. Wände an festem Terrain bleiben opak. */
        boolean fluidWall = this.appearance.isTranslucent(block);
        int ao = fluidWall ? 0xFF : packedAo >= 0 ? packedAo
                : this.wallGeometryAo(face, xa, za, xb, zb, bottom, top);

        if (this.stats != null) {
            if (fluidWall) this.stats.wallWater++; else this.stats.wallTerrain++;
        }
        this.ensureCapacity(fluidWall);
        this.putVertex(fluidWall, xa, bottom, za, 0F, v, layer,
                brightness * aoFromPacked(ao, 0), tint, bottomSkyLight);
        this.putVertex(fluidWall, xb, bottom, zb, u, v, layer,
                brightness * aoFromPacked(ao, 1), tint, bottomSkyLight);
        this.putVertex(fluidWall, xb, top, zb, u, 0F, layer,
                brightness * aoFromPacked(ao, 2), tint, topSkyLight);
        this.putVertex(fluidWall, xa, top, za, 0F, 0F, layer,
                brightness * aoFromPacked(ao, 3), tint, topSkyLight);

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
     * Der 5. Int trägt das Licht (s. {@link ChunkMesher#VERTEX_SIZE}); freie Oberflächen
     * bekommen Voll-Himmel, Geometrie unter LOD-Wasser die analytische Tiefen-Näherung.
     */
    private void putVertex(boolean translucent, float x, float y, float z, float u, float v,
                           int layer, float brightness, int tint, int skyLight) {
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
        /* Skylight in Bits 0-3, Blocklicht bleibt 0 (Bits 4-7): Fernregionen simulieren keine
           Lichtausbreitung, nur die deterministische Wasser-Dämpfung der sichtbaren Geometrie. */
        buf[i++] = Math.clamp(skyLight, 0, 15);
        if (translucent) this.viTranslucent = i; else this.viOpaque = i;
    }
}
