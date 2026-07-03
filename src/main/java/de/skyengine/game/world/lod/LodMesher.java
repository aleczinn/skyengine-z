package de.skyengine.game.world.lod;

import de.skyengine.game.world.block.model.BlockModels;
import de.skyengine.game.world.chunk.ChunkMesher;
import de.skyengine.game.world.lod.LodManager.LodMeshResult;

import java.util.Arrays;

/**
 * Mesht LOD-Regionen <b>blockbasiert</b> (Voxel-Optik wie echtes Terrain): pro Zelle
 * (Zellgröße 2^Level Blöcke, global ausgerichtetes Raster) ein flaches Top-Quad auf der
 * Deckflächen-Oberkante plus senkrechte Wände zu niedrigeren Nachbarzellen — Stufen in
 * Zellgröße statt geglätteter Surface, damit die LOD-Level sichtbar sind und der Übergang
 * zu L0 konsistent bleibt. Helligkeit über {@link BlockModels#FACE_BRIGHTNESS} wie bei
 * echten Blöcken; Texturen/Tints pro Oberflächenblock aus der {@link LodBlockAppearance}.
 *
 * <p>Läuft auf den Chunk-Workern und liest ausschließlich die {@link LodDataSource},
 * nie Chunk-Daten. Ausgabe: gepacktes 16-Byte-Vertex-Format des {@link ChunkMesher}
 * (bestehende OPAQUE-Arena, bestehender Shader). Eine Instanz pro Worker-Thread
 * (wiederverwendete Puffer, siehe ThreadLocal im {@link LodManager}).
 *
 * <p>Gegen Quad-Explosion: 1D-Greedy-Merge — Tops und Nord-/Süd-Wände als Runs entlang x,
 * West-/Ost-Wände entlang z; Deckel {@link #MAX_MERGE_BLOCKS} (UV-Format trägt max ~63,
 * GL_REPEAT tiled wie beim Greedy Meshing).
 */
public final class LodMesher {

    /** Kantenlänge einer LOD-Region in Blöcken (4x4 Chunks — passt ins u16-8.8-Positionsformat). */
    public static final int REGION_BLOCKS = 128;

    /** Halbe Diagonale einer Region — Toleranz für Kreis-Überlappungstests. */
    public static final float HALF_DIAG = 90.6F;

    /* Merge-/UV-Deckel in Blöcken (UV-Fixed-Point 6.10 trägt max ~63; 32 lässt Reserve). */
    private static final int MAX_MERGE_BLOCKS = 32;

    /* Skirt-Tiefe an Regionskanten zu anders-leveligen Nachbarn: deren gröbere/feinere
       Samples weichen von unseren Zell-Tops ab — die Zusatzwand ins Erdreich verdeckt
       die Restspalte. Nur wenige Randzellen betroffen. */
    private static final int SKIRT_BLOCKS = 8;

    /* Face-Indizes wie BlockModels: 0=top, 2=north(-z), 3=south(+z), 4=west(-x), 5=east(+x) */

    private static final int QUAD_INTS = 4 * ChunkMesher.VERTEX_SIZE;

    /* --- Wiederverwendete Puffer (eine Instanz pro Worker-Thread) --- */
    private long[] cells = new long[0];        // (n+2)² Samples inkl. Randring
    private boolean[] clipped = new boolean[0];
    private int[] out = new int[16384];
    private int vi;
    private int stride, cellSize, cellCount;   // Kontext des laufenden mesh()-Aufrufs
    private float minBottom, maxTop;

    /**
     * Mesht eine Region. Worker-Thread, reine Daten, kein GL.
     *
     * @param epoch    Settings-Epoche des LodManagers (Bookkeeping, wandert ins Ergebnis)
     * @param pcx,pcz  Spieler-Chunk zum Submit-Zeitpunkt — Mittelpunkt des Clip-Kreises
     * @param px,pz    Spielerposition der Desired-Berechnung — Basis der Level-Zuordnung
     *                 (muss zur Zuordnung im LodManager passen, sonst falsche Nachbar-Level)
     */
    public LodMeshResult mesh(LodDataSource source, LodBlockAppearance appearance, LodConfig config,
                              int level, int rx, int rz, int epoch, int pcx, int pcz,
                              double px, double pz) {
        int s = config.cellSize(level);
        int n = REGION_BLOCKS / s;
        this.stride = n + 2;                    // Zellen -1..n (Randring für Wände)
        this.cellSize = s;
        this.cellCount = n;
        int baseX = rx * REGION_BLOCKS;
        int baseZ = rz * REGION_BLOCKS;

        if (this.cells.length < this.stride * this.stride) this.cells = new long[this.stride * this.stride];
        if (this.clipped.length < n * n) this.clipped = new boolean[n * n];
        this.vi = 0;
        this.minBottom = Float.MAX_VALUE;
        this.maxTop = -Float.MAX_VALUE;

        /* 1. Zellen sampeln (inkl. Randring). Zellen fremder Regionen werden auf DEREN
           Zellraster gesampelt — deterministisch identisch mit dem, was die Nachbarregion
           selbst baut (gleiche pure levelAt-Zuordnung, keine Sync-Logik). */
        for (int cz = -1; cz <= n; cz++) {
            for (int cx = -1; cx <= n; cx++) {
                this.cells[(cz + 1) * this.stride + (cx + 1)] =
                        sampleCell(source, config, baseX + cx * s, baseZ + cz * s, s, rx, rz, px, pz);
            }
        }

        /* 2. rd-Clipping: Zellen im echten Chunk-Bereich überspringen (Mittelpunkt-Test
           gegen den Clip-Kreis um den Spieler-Chunk). */
        float clip = config.clipRadius();
        double qx = pcx * 32 + 16, qz = pcz * 32 + 16;
        double rcx = baseX + REGION_BLOCKS / 2.0 - qx;
        double rcz = baseZ + REGION_BLOCKS / 2.0 - qz;
        boolean mayClip = clip > 0 && Math.sqrt(rcx * rcx + rcz * rcz) - HALF_DIAG < clip;
        double clipSq = (double) clip * clip;
        for (int cz = 0; cz < n; cz++) {
            for (int cx = 0; cx < n; cx++) {
                boolean c = false;
                if (mayClip) {
                    double wx = baseX + (cx + 0.5) * s - qx;
                    double wz = baseZ + (cz + 0.5) * s - qz;
                    c = wx * wx + wz * wz < clipSq;
                }
                this.clipped[cz * n + cx] = c;
            }
        }

        /* 3. Skirt-Kanten: Nachbarregion mit anderem Level? */
        boolean skirtW = neighborLevel(config, rx - 1, rz, px, pz) != level;
        boolean skirtE = neighborLevel(config, rx + 1, rz, px, pz) != level;
        boolean skirtN = neighborLevel(config, rx, rz - 1, px, pz) != level;
        boolean skirtS = neighborLevel(config, rx, rz + 1, px, pz) != level;

        /* 4. Tops (Runs entlang x) */
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

                int h = LodDataSource.height(sample);
                int block = LodDataSource.block(sample);
                this.emitTop(appearance, block, cx * s, cz * s, (cx + run) * s, (cz + 1) * s, h + 1);
                cx += run;
            }
        }

        /* 5. Wände: die höhere Zelle besitzt die Wand (wie Block-Faces). */
        for (int cz = 0; cz < n; cz++) {
            this.wallsAlongX(appearance, cz, -1, 2, skirtN && cz == 0, s, maxRun);       // north
            this.wallsAlongX(appearance, cz, +1, 3, skirtS && cz == n - 1, s, maxRun);   // south
        }
        for (int cx = 0; cx < n; cx++) {
            this.wallsAlongZ(appearance, cx, -1, 4, skirtW && cx == 0, s, maxRun);       // west
            this.wallsAlongZ(appearance, cx, +1, 5, skirtE && cx == n - 1, s, maxRun);   // east
        }

        int[] data = this.vi == 0 ? new int[0] : Arrays.copyOf(this.out, this.vi);
        float minY = this.vi == 0 ? 0F : this.minBottom;
        float maxY = this.vi == 0 ? 0F : this.maxTop;
        return new LodMeshResult(level, rx, rz, epoch, pcx, pcz, data, minY, maxY);
    }

    /* ------------------------- Sampling ------------------------- */

    /**
     * Sample einer Zelle mit Ursprung (wx,wz): innerhalb der eigenen Region aufs eigene
     * Raster (s), sonst aufs Raster des Nachbar-Levels ausgerichtet.
     */
    private static long sampleCell(LodDataSource source, LodConfig config, int wx, int wz, int s,
                                   int rx, int rz, double px, double pz) {
        int rxc = Math.floorDiv(wx, REGION_BLOCKS);
        int rzc = Math.floorDiv(wz, REGION_BLOCKS);
        if (rxc == rx && rzc == rz) return source.sampleSurface(wx, wz, s);

        int s2 = config.cellSize(neighborLevel(config, rxc, rzc, px, pz));
        return source.sampleSurface(Math.floorDiv(wx, s2) * s2, Math.floorDiv(wz, s2) * s2, s2);
    }

    private static int neighborLevel(LodConfig config, int nrx, int nrz, double px, double pz) {
        double dx = (nrx + 0.5) * REGION_BLOCKS - px;
        double dz = (nrz + 0.5) * REGION_BLOCKS - pz;
        return config.levelAt(Math.sqrt(dx * dx + dz * dz));
    }

    private long cell(int cx, int cz) {
        return this.cells[(cz + 1) * this.stride + (cx + 1)];
    }

    /* ------------------------- Wände ------------------------- */

    /** Nord-/Süd-Wände einer Zellreihe, Runs entlang x. dz = Nachbar-Offset, face = 2/3. */
    private void wallsAlongX(LodBlockAppearance appearance, int cz, int dz, int face,
                             boolean skirt, int s, int maxRun) {
        int n = this.cellCount;
        int cx = 0;
        while (cx < n) {
            if (this.clipped[cz * n + cx]) {
                cx++;
                continue;
            }
            long sample = this.cell(cx, cz);
            long nSample = this.cell(cx, cz + dz);
            int h = LodDataSource.height(sample);
            int nh = LodDataSource.height(nSample);
            if (!skirt && nh >= h) {
                cx++;
                continue;
            }
            int run = 1;
            while (cx + run < n && run < maxRun && !this.clipped[cz * n + cx + run]
                    && this.cell(cx + run, cz) == sample && this.cell(cx + run, cz + dz) == nSample) run++;

            int top = h + 1;
            int bottom = Math.max(0, Math.min(nh, h) + 1 - (skirt ? SKIRT_BLOCKS : 0));
            float x0 = cx * s, x1 = (cx + run) * s;
            float z = (dz < 0 ? cz : cz + 1) * s;
            int block = LodDataSource.block(sample);
            if (face == 2) {
                this.emitWall(appearance, block, face, x1, z, x0, z, bottom, top);
            } else {
                this.emitWall(appearance, block, face, x0, z, x1, z, bottom, top);
            }
            cx += run;
        }
    }

    /** West-/Ost-Wände einer Zellspalte, Runs entlang z. dx = Nachbar-Offset, face = 4/5. */
    private void wallsAlongZ(LodBlockAppearance appearance, int cx, int dx, int face,
                             boolean skirt, int s, int maxRun) {
        int n = this.cellCount;
        int cz = 0;
        while (cz < n) {
            if (this.clipped[cz * n + cx]) {
                cz++;
                continue;
            }
            long sample = this.cell(cx, cz);
            long nSample = this.cell(cx + dx, cz);
            int h = LodDataSource.height(sample);
            int nh = LodDataSource.height(nSample);
            if (!skirt && nh >= h) {
                cz++;
                continue;
            }
            int run = 1;
            while (cz + run < n && run < maxRun && !this.clipped[(cz + run) * n + cx]
                    && this.cell(cx, cz + run) == sample && this.cell(cx + dx, cz + run) == nSample) run++;

            int top = h + 1;
            int bottom = Math.max(0, Math.min(nh, h) + 1 - (skirt ? SKIRT_BLOCKS : 0));
            float z0 = cz * s, z1 = (cz + run) * s;
            float x = (dx < 0 ? cx : cx + 1) * s;
            int block = LodDataSource.block(sample);
            if (face == 4) {
                this.emitWall(appearance, block, face, x, z0, x, z1, bottom, top);
            } else {
                this.emitWall(appearance, block, face, x, z1, x, z0, bottom, top);
            }
            cz += run;
        }
    }

    /* ------------------------- Quad-Emission ------------------------- */

    /** Flaches Top-Quad auf Höhe y (CCW von oben, u=x / v=z wie BlockModels-Top). */
    private void emitTop(LodBlockAppearance appearance, int block,
                         float x0, float z0, float x1, float z1, float y) {
        int layer = appearance.topLayer(block);
        int tint = appearance.tint(block);
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
     * Senkrechte Wand von bottom bis top zwischen den Bodenpunkten A=(xa,za) und B=(xb,zb)
     * (A→B = u-Richtung; Reihenfolge der Aufrufer wählt die CCW-Sicht von außen).
     * v=0 liegt an der Oberkante (Textur-Oben = Face-Oben, wie BlockModels).
     */
    private void emitWall(LodBlockAppearance appearance, int block, int face,
                          float xa, float za, float xb, float zb, float bottom, float top) {
        int layer = appearance.sideLayer(block);
        int tint = appearance.tint(block);
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

    /** Packt einen Vertex ins Chunk-Format (Konstanten aus {@link ChunkMesher}, Bias +1). */
    private void putVertex(float x, float y, float z, float u, float v,
                           int layer, float brightness, int tint) {
        int px = (int) ((x + 1F) * ChunkMesher.POS_SCALE + 0.5F);
        int py = (int) ((y + 1F) * ChunkMesher.POS_SCALE + 0.5F);
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
