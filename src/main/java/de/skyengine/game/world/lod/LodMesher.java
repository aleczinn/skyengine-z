package de.skyengine.game.world.lod;

import de.skyengine.game.world.block.model.BlockModels;
import de.skyengine.game.world.chunk.ChunkMesher;
import de.skyengine.game.world.chunk.FluidGeometry;
import de.skyengine.game.world.lod.LodManager.LodMeshResult;

import java.util.Arrays;

/**
 * Mesht LOD-Regionen <b>blockbasiert</b> (Voxel-Optik wie echtes Terrain): pro Zelle
 * (Stride 2^Level Blöcke, global ausgerichtetes Raster) ein flaches Top-Quad auf der
 * Deckflächen-Oberkante plus senkrechte Wände zu niedrigeren Nachbarzellen. Helligkeit über
 * {@link BlockModels#FACE_BRIGHTNESS}; Texturen/Tints aus der {@link LodBlockAppearance};
 * Fluid-Zellen liegen auf der echten Quellhöhe ({@link FluidGeometry#SOURCE_HEIGHT}).
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

    /* Face-Indizes wie BlockModels: 0=top, 2=north(-z), 3=south(+z), 4=west(-x), 5=east(+x) */

    private static final int QUAD_INTS = 4 * ChunkMesher.VERTEX_SIZE;

    /* --- Wiederverwendete Puffer (eine Instanz pro Worker-Thread) --- */
    private long[] cells = new long[0];        // (n+2)² Samples inkl. Randring
    private boolean[] clipped = new boolean[0];
    private int[] out = new int[16384];
    private int vi;
    private int stride, cellCount;             // Kontext des laufenden mesh()-Aufrufs
    private int yBase, edgeSkirt;
    private LodBlockAppearance appearance;
    private float minBottom, maxTop;           // absolut (fürs Frustum-AABB)

    /** Skirt-Tiefe an Regionsrand-Kanten, wächst mit der Zellgröße (s. MAX_SKIRT-Herleitung). */
    private static int edgeSkirtOf(int level) {
        return Math.min(BASE_SKIRT << level, MAX_SKIRT);
    }

    /**
     * Mesht eine Region. Worker-Thread, reine Daten, kein GL.
     *
     * @param mask   16-Bit-Maske der 4×4 Chunks: gesetzt = Chunk zeigt echtes Terrain → clippen
     * @param ax,az  Anker (Blockkoordinaten des Spieler-Regionszentrums der Desired-Epoche) —
     *               Basis der Level-Zuordnung, muss zum LodManager passen (pure Funktion)
     */
    public LodMeshResult mesh(LodDataSource source, LodBlockAppearance appearance, LodConfig config,
                              int level, int rx, int rz, int epoch, int mask, int ax, int az) {
        int s = config.cellSize(level);
        int n = REGION_BLOCKS / s;
        this.stride = n + 2;                    // Zellen -1..n (Randring für Wände)
        this.cellCount = n;
        this.appearance = appearance;
        this.edgeSkirt = edgeSkirtOf(level);
        int baseX = rx * REGION_BLOCKS;
        int baseZ = rz * REGION_BLOCKS;

        /* Komplett von echtem Terrain bedeckt → nichts zu meshen (spart das Sampling). */
        if (mask == 0xFFFF) {
            return new LodMeshResult(level, rx, rz, epoch, mask, 0, new int[0], 0F, 0F);
        }

        if (this.cells.length < this.stride * this.stride) this.cells = new long[this.stride * this.stride];
        if (this.clipped.length < n * n) this.clipped = new boolean[n * n];
        this.vi = 0;
        this.minBottom = Float.MAX_VALUE;
        this.maxTop = -Float.MAX_VALUE;

        /* 1. Zellen sampeln (inkl. Randring), rein am Zellmittel — deterministisch identisch
           aus Sicht aller Regionen. Zellen fremder Regionen auf DEREN Zellraster (gleiche
           pure levelAt-Zuordnung wie im LodManager). */
        int minHeight = Integer.MAX_VALUE;
        for (int cz = -1; cz <= n; cz++) {
            for (int cx = -1; cx <= n; cx++) {
                long sample = sampleCell(source, config, baseX + cx * s, baseZ + cz * s, s, rx, rz, ax, az);
                this.cells[(cz + 1) * this.stride + (cx + 1)] = sample;
                int h = LodDataSource.height(sample);
                if (h < minHeight) minHeight = h;
            }
        }

        /* yBase: u16 trägt nur ~254 Blöcke Spanne — relativ zur tiefsten Geometrie packen. */
        this.yBase = Math.max(0, minHeight - this.edgeSkirt - 2);

        /* 2. Clip-Maske pro Zelle (Zellen liegen raster-aligned in genau einem Chunk). */
        int cellsPerChunk = 32 / s;
        for (int cz = 0; cz < n; cz++) {
            for (int cx = 0; cx < n; cx++) {
                int bit = (cz / cellsPerChunk) * 4 + (cx / cellsPerChunk);
                this.clipped[cz * n + cx] = (mask & (1 << bit)) != 0;
            }
        }

        /* 3. Tops (Runs entlang x) */
        int maxRun = Math.max(1, MAX_MERGE_BLOCKS / s);
        for (int cz = 0; cz < n; cz++) {
            int cx = 0;
            while (cx < n) {
                if (this.clipped[cz * n + cx]) {
                    cx++;
                    continue;
                }
                long sample = this.cell(cx, cz);
                int run = 1;
                while (cx + run < n && run < maxRun && !this.clipped[cz * n + cx + run]
                        && this.cell(cx + run, cz) == sample) run++;

                this.emitTop(LodDataSource.block(sample),
                        cx * s, cz * s, (cx + run) * s, (cz + 1) * s, this.topOf(sample));
                cx += run;
            }
        }

        /* 4. Wände: die höhere Zelle besitzt die Wand; an Regionsrand-Kanten IMMER mit Skirt. */
        for (int cz = 0; cz < n; cz++) {
            this.wallsAlongX(cz, -1, 2, cz == 0, s, maxRun);       // north
            this.wallsAlongX(cz, +1, 3, cz == n - 1, s, maxRun);   // south
        }
        for (int cx = 0; cx < n; cx++) {
            this.wallsAlongZ(cx, -1, 4, cx == 0, s, maxRun);       // west
            this.wallsAlongZ(cx, +1, 5, cx == n - 1, s, maxRun);   // east
        }

        int[] data = this.vi == 0 ? new int[0] : Arrays.copyOf(this.out, this.vi);
        float minY = this.vi == 0 ? 0F : this.minBottom;
        float maxY = this.vi == 0 ? 0F : this.maxTop;
        this.appearance = null;
        return new LodMeshResult(level, rx, rz, epoch, mask, this.yBase, data, minY, maxY);
    }

    /* ------------------------- Sampling ------------------------- */

    /**
     * Sample einer Zelle mit Ursprung (wx,wz): innerhalb der eigenen Region aufs eigene
     * Raster (s), sonst aufs Raster des Nachbar-Levels ausgerichtet.
     */
    private static long sampleCell(LodDataSource source, LodConfig config, int wx, int wz, int s,
                                   int rx, int rz, int ax, int az) {
        int rxc = Math.floorDiv(wx, REGION_BLOCKS);
        int rzc = Math.floorDiv(wz, REGION_BLOCKS);
        if (rxc == rx && rzc == rz) return source.sampleSurface(wx, wz, s);

        int s2 = config.cellSize(neighborLevel(config, rxc, rzc, ax, az));
        return source.sampleSurface(Math.floorDiv(wx, s2) * s2, Math.floorDiv(wz, s2) * s2, s2);
    }

    private static int neighborLevel(LodConfig config, int nrx, int nrz, int ax, int az) {
        double dx = (nrx + 0.5) * REGION_BLOCKS - ax;
        double dz = (nrz + 0.5) * REGION_BLOCKS - az;
        return config.levelAt(Math.sqrt(dx * dx + dz * dz));
    }

    private long cell(int cx, int cz) {
        return this.cells[(cz + 1) * this.stride + (cx + 1)];
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

    /** Sichtbare Oberkante einer Zelle: Fluide auf Quellhöhe (8/9), sonst Blockoberkante (+1). */
    private float topOf(long sample) {
        int h = LodDataSource.height(sample);
        return this.appearance.isFluid(LodDataSource.block(sample))
                ? h + FluidGeometry.SOURCE_HEIGHT : h + 1F;
    }

    /* ------------------------- Wände ------------------------- */

    /** Nord-/Süd-Wände einer Zellreihe, Runs entlang x. dz = Nachbar-Offset, face = 2/3. */
    private void wallsAlongX(int cz, int dz, int face, boolean edge, int s, int maxRun) {
        int n = this.cellCount;
        int cx = 0;
        while (cx < n) {
            if (this.clipped[cz * n + cx]) {
                cx++;
                continue;
            }
            long sample = this.cell(cx, cz);
            long nSample = this.cell(cx, cz + dz);
            float top = this.topOf(sample);
            float nTop = this.topOf(nSample);
            /* Skirt an Regionsrand- UND Masken-Kanten (geclippter Nachbar = L0-Naht) */
            boolean nClipped = this.neighborClipped(cx, cz + dz);
            boolean skirt = edge || nClipped;
            if (!skirt && nTop >= top) {
                cx++;
                continue;
            }
            int run = 1;
            while (cx + run < n && run < maxRun && !this.clipped[cz * n + cx + run]
                    && this.cell(cx + run, cz) == sample && this.cell(cx + run, cz + dz) == nSample
                    && this.neighborClipped(cx + run, cz + dz) == nClipped) run++;

            float bottom = Math.max(0F, Math.min(nTop, top) - (skirt ? this.edgeSkirt : 0F));
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

    /** West-/Ost-Wände einer Zellspalte, Runs entlang z. dx = Nachbar-Offset, face = 4/5. */
    private void wallsAlongZ(int cx, int dx, int face, boolean edge, int s, int maxRun) {
        int n = this.cellCount;
        int cz = 0;
        while (cz < n) {
            if (this.clipped[cz * n + cx]) {
                cz++;
                continue;
            }
            long sample = this.cell(cx, cz);
            long nSample = this.cell(cx + dx, cz);
            float top = this.topOf(sample);
            float nTop = this.topOf(nSample);
            /* Skirt an Regionsrand- UND Masken-Kanten (geclippter Nachbar = L0-Naht) */
            boolean nClipped = this.neighborClipped(cx + dx, cz);
            boolean skirt = edge || nClipped;
            if (!skirt && nTop >= top) {
                cz++;
                continue;
            }
            int run = 1;
            while (cz + run < n && run < maxRun && !this.clipped[(cz + run) * n + cx]
                    && this.cell(cx, cz + run) == sample && this.cell(cx + dx, cz + run) == nSample
                    && this.neighborClipped(cx + dx, cz + run) == nClipped) run++;

            float bottom = Math.max(0F, Math.min(nTop, top) - (skirt ? this.edgeSkirt : 0F));
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

    /* ------------------------- Quad-Emission ------------------------- */

    /** Flaches Top-Quad auf absoluter Höhe y (CCW von oben, u=x / v=z wie BlockModels-Top). */
    private void emitTop(int block, float x0, float z0, float x1, float z1, float y) {
        int layer = this.appearance.topLayer(block);
        int tint = this.appearance.tint(block);
        float brightness = BlockModels.FACE_BRIGHTNESS[0];
        float u = x1 - x0, v = z1 - z0;

        this.ensureCapacity();
        this.putVertex(x0, y, z0, 0F, 0F, layer, brightness, tint);
        this.putVertex(x0, y, z1, 0F, v, layer, brightness, tint);
        this.putVertex(x1, y, z1, u, v, layer, brightness, tint);
        this.putVertex(x1, y, z0, u, 0F, layer, brightness, tint);

        if (y > this.maxTop) this.maxTop = y;
        if (y < this.minBottom) this.minBottom = y;
    }

    /**
     * Senkrechte Wand von bottom bis top (absolut) zwischen den Bodenpunkten A=(xa,za) und
     * B=(xb,zb) (A→B = u-Richtung; Aufrufer wählt die CCW-Sicht von außen). v=0 an der
     * Oberkante (Textur-Oben = Face-Oben, wie BlockModels).
     */
    private void emitWall(int block, int face, float xa, float za, float xb, float zb,
                          float bottom, float top) {
        int layer = this.appearance.sideLayer(block);
        int tint = this.appearance.tint(block);
        float brightness = BlockModels.FACE_BRIGHTNESS[face];
        float u = Math.abs(xb - xa) + Math.abs(zb - za);
        float v = Math.min(top - bottom, MAX_MERGE_BLOCKS);

        this.ensureCapacity();
        this.putVertex(xa, bottom, za, 0F, v, layer, brightness, tint);
        this.putVertex(xb, bottom, zb, u, v, layer, brightness, tint);
        this.putVertex(xb, top, zb, u, 0F, layer, brightness, tint);
        this.putVertex(xa, top, za, 0F, 0F, layer, brightness, tint);

        if (top > this.maxTop) this.maxTop = top;
        if (bottom < this.minBottom) this.minBottom = bottom;
    }

    private void ensureCapacity() {
        if (this.vi + QUAD_INTS > this.out.length) {
            this.out = Arrays.copyOf(this.out, this.out.length * 2);
        }
    }

    /**
     * Packt einen Vertex ins Chunk-Format (Konstanten aus {@link ChunkMesher}, Bias +1);
     * y wird relativ zu {@link #yBase} gepackt (Renderer addiert yBase im Draw-Offset).
     * Clamp als Sicherheitsnetz gegen Format-Überlauf (wie ChunkMesher.fixedPos).
     */
    private void putVertex(float x, float y, float z, float u, float v,
                           int layer, float brightness, int tint) {
        int px = (int) ((x + 1F) * ChunkMesher.POS_SCALE + 0.5F);
        int py = Math.clamp((int) ((y - this.yBase + 1F) * ChunkMesher.POS_SCALE + 0.5F), 0, 0xFFFF);
        int pz = (int) ((z + 1F) * ChunkMesher.POS_SCALE + 0.5F);
        int pu = (int) ((u + 1F) * ChunkMesher.UV_SCALE + 0.5F);
        int pv = (int) ((v + 1F) * ChunkMesher.UV_SCALE + 0.5F);
        int r = Math.clamp((int) (((tint >> 16) & 0xFF) * brightness + 0.5F), 0, 255);
        int g = Math.clamp((int) (((tint >> 8) & 0xFF) * brightness + 0.5F), 0, 255);
        int b = Math.clamp((int) ((tint & 0xFF) * brightness + 0.5F), 0, 255);
        this.out[this.vi++] = px | (py << 16);
        this.out[this.vi++] = pz | (pu << 16);
        this.out[this.vi++] = pv | (layer << 16);
        this.out[this.vi++] = r | (g << 8) | (b << 16);
    }
}
