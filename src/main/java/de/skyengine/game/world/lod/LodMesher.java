package de.skyengine.game.world.lod;

import de.skyengine.game.world.chunk.ChunkMesher;
import de.skyengine.game.world.generator.WorldGenerator;
import de.skyengine.game.world.lod.LodManager.LodMeshResult;

import java.util.Arrays;

/**
 * Mesht LOD-Regionen als Heightmap-Surface: pro Zelle (2^level Blöcke) ein Quad, dessen Ecken
 * auf der echten Deckflächen-Oberkante ({@code sampleHeight + 1}) liegen — kein Voxel-LOD,
 * kein Underlay. Läuft auf den Chunk-Workern und liest ausschließlich
 * {@link WorldGenerator#sampleHeight} (pure Funktion), nie Chunk-Daten.
 *
 * <p>Ausgabe ist das gepackte 16-Byte-Vertex-Format des {@link ChunkMesher} — die Meshes landen
 * in der bestehenden OPAQUE-Arena und werden vom bestehenden Chunk-Shader gezeichnet.
 */
public final class LodMesher {

    /** Kantenlänge einer LOD-Region in Blöcken (4x4 Chunks — passt ins u16-8.8-Positionsformat). */
    public static final int REGION_BLOCKS = 128;

    /** Halbe Diagonale einer Region — Toleranz für Kreis-Überlappungstests. */
    public static final float HALF_DIAG = 90.6F;

    /** Ints pro Quad (4 Vertices im gepackten Format). */
    private static final int QUAD_INTS = 4 * ChunkMesher.VERTEX_SIZE;

    private LodMesher() {}

    /**
     * Radius in Blöcken, innerhalb dessen LOD-Zellen übersprungen werden: der äußerste geladene
     * Chunk-Ring wird nie gemesht (Nachbarn fehlen) und die Mesh-Grenze ist ausgefranst
     * (8-Nachbarn-Bedingung im Kreis) → rd-2 plus 16 Toleranz für die Chunk-Quantisierung der
     * Spielerposition. Überlappung mit echtem Terrain ist unkritisch (Section-Commands zeichnen
     * zuerst und gewinnen den Depth-Test), Lücken zeigen dagegen Himmel an Hang-Silhouetten.
     */
    public static float clipRadius(int renderDistance) {
        return (renderDistance - 2) * 32.0F - 16.0F;
    }

    /**
     * Mesht eine Region. Worker-Thread, reine Daten, kein GL.
     *
     * @param epoch    Settings-Epoche des LodManagers (Bookkeeping, wandert ins Ergebnis)
     * @param pcx,pcz  Spieler-Chunk zum Submit-Zeitpunkt — Mittelpunkt des Clip-Kreises
     * @param px,pz    Spielerposition der Desired-Berechnung — Basis der Level-Zuordnung
     *                 (muss zur Zuordnung im LodManager passen, sonst falsches Stitching)
     */
    public static LodMeshResult mesh(WorldGenerator generator, int level, int rx, int rz,
                                     int epoch, int pcx, int pcz, double px, double pz,
                                     int renderDistance, int textureLayer) {
        int s = 1 << level;                 // Zellgröße in Blöcken
        int n = REGION_BLOCKS / s;          // Zellen pro Achse
        int stride = n + 1;                 // Gitterpunkte pro Achse
        int baseX = rx * REGION_BLOCKS;
        int baseZ = rz * REGION_BLOCKS;

        /* 1. Höhen-Gitter sampeln (Oberkante der Deckfläche = Höhe + 1). Gitterpunkte an
           Regionsgrenzen sind bei gleichem Level identisch (pure Funktion) → wasserdicht. */
        float[] h = new float[stride * stride];
        float minY = Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        for (int gz = 0; gz <= n; gz++) {
            for (int gx = 0; gx <= n; gx++) {
                float y = generator.sampleHeight(baseX + gx * s, baseZ + gz * s) + 1;
                h[gz * stride + gx] = y;
                if (y < minY) minY = y;
                if (y > maxY) maxY = y;
            }
        }

        /* 2. Stitching gegen T-Junction-Risse: grenzt eine Kante an ein gröberes Nachbar-Level,
           snappen Randpunkte, die kein Gitterpunkt des Nachbarn sind, auf dessen Interpolation.
           Die feinere Seite passt sich an; die Level kommen aus derselben puren Zuordnung
           wie im LodManager (keine Sync-Logik nötig). */
        stitchEdge(h, n, s, neighborCellSize(rx - 1, rz, px, pz, renderDistance), 0, stride);          // west
        stitchEdge(h, n, s, neighborCellSize(rx + 1, rz, px, pz, renderDistance), n, stride);          // east
        stitchEdge(h, n, s, neighborCellSize(rx, rz - 1, px, pz, renderDistance), 0, 1);               // north
        stitchEdge(h, n, s, neighborCellSize(rx, rz + 1, px, pz, renderDistance), n * stride, 1);      // south

        /* 3. Helligkeit pro Gitterpunkt aus der Hangneigung (kein AO in der Ferne; flach = 1.0
           wie echte Deckflächen, steile Hänge dunkeln Richtung Seitenflächen-Helligkeit ab). */
        float[] bright = new float[stride * stride];
        for (int gz = 0; gz <= n; gz++) {
            for (int gx = 0; gx <= n; gx++) {
                int x0 = Math.max(gx - 1, 0), x1 = Math.min(gx + 1, n);
                int z0 = Math.max(gz - 1, 0), z1 = Math.min(gz + 1, n);
                float dx = (h[gz * stride + x1] - h[gz * stride + x0]) / ((x1 - x0) * s);
                float dz = (h[z1 * stride + gx] - h[z0 * stride + gx]) / ((z1 - z0) * s);
                float slope = (float) Math.sqrt(dx * dx + dz * dz);
                bright[gz * stride + gx] = Math.max(0.6F, 1.0F - slope * 0.25F);
            }
        }

        /* 4. Quads emittieren; Zellen im echten Chunk-Bereich überspringen (rd-Clipping). */
        float clip = clipRadius(renderDistance);
        double qx = pcx * 32 + 16, qz = pcz * 32 + 16;   // Clip-Kreis um den Spieler-Chunk
        double rcx = baseX + REGION_BLOCKS / 2.0 - qx;
        double rcz = baseZ + REGION_BLOCKS / 2.0 - qz;
        boolean mayClip = clip > 0 && Math.sqrt(rcx * rcx + rcz * rcz) - HALF_DIAG < clip;
        double clipSq = (double) clip * clip;

        /* UV tiled pro Zelle (1 Textur pro Block, wie Greedy Meshing); Format trägt max ~63. */
        float uvMax = Math.min(s, 32);

        int[] out = new int[n * n * QUAD_INTS];
        int vi = 0;
        for (int cz = 0; cz < n; cz++) {
            for (int cx = 0; cx < n; cx++) {
                if (mayClip) {
                    double wx = baseX + (cx + 0.5) * s - qx;
                    double wz = baseZ + (cz + 0.5) * s - qz;
                    if (wx * wx + wz * wz < clipSq) continue;
                }
                int i00 = cz * stride + cx;
                int i01 = i00 + stride;         // z+
                int i10 = i00 + 1;              // x+
                int i11 = i01 + 1;
                float x0 = cx * s, x1 = x0 + s;
                float z0 = cz * s, z1 = z0 + s;
                /* Ecken-Reihenfolge = Top-Face aus BlockModels (CCW von oben, u=x / v=z) */
                vi = putVertex(out, vi, x0, h[i00], z0, 0F, 0F, textureLayer, bright[i00]);
                vi = putVertex(out, vi, x0, h[i01], z1, 0F, uvMax, textureLayer, bright[i01]);
                vi = putVertex(out, vi, x1, h[i11], z1, uvMax, uvMax, textureLayer, bright[i11]);
                vi = putVertex(out, vi, x1, h[i10], z0, uvMax, 0F, textureLayer, bright[i10]);
            }
        }

        int[] data = vi == out.length ? out : Arrays.copyOf(out, vi);
        return new LodMeshResult(level, rx, rz, epoch, pcx, pcz, data, minY, maxY);
    }

    /** Zellgröße des Nachbar-Levels (aus derselben puren Zuordnung wie der LodManager). */
    private static int neighborCellSize(int nrx, int nrz, double px, double pz, int renderDistance) {
        double cx = (nrx + 0.5) * REGION_BLOCKS - px;
        double cz = (nrz + 0.5) * REGION_BLOCKS - pz;
        return 1 << LodManager.levelAt(Math.sqrt(cx * cx + cz * cz), renderDistance);
    }

    /**
     * Snappt Randpunkte einer Kante, die kein Gitterpunkt des gröberen Nachbarn (Zellgröße s2)
     * sind, auf die lineare Interpolation seiner Gitterpunkte. Eckpunkte (Vielfache von
     * REGION_BLOCKS) bleiben immer unberührt.
     */
    private static void stitchEdge(float[] h, int n, int s, int s2, int base, int step) {
        if (s2 <= s) return;
        int grid = Math.min(s2, REGION_BLOCKS);
        for (int i = 1; i < n; i++) {
            int p = i * s;
            if (p % grid == 0) continue;
            int a0 = (p / grid) * grid;
            int a1 = a0 + grid;
            float t = (p - a0) / (float) grid;
            float h0 = h[base + (a0 / s) * step];
            float h1 = h[base + (a1 / s) * step];
            h[base + i * step] = h0 + (h1 - h0) * t;
        }
    }

    /** Packt einen Vertex ins Chunk-Format (Konstanten aus {@link ChunkMesher}, Bias +1). */
    private static int putVertex(int[] out, int i, float x, float y, float z,
                                 float u, float v, int layer, float brightness) {
        int px = (int) ((x + 1F) * ChunkMesher.POS_SCALE + 0.5F);
        int py = (int) ((y + 1F) * ChunkMesher.POS_SCALE + 0.5F);
        int pz = (int) ((z + 1F) * ChunkMesher.POS_SCALE + 0.5F);
        int pu = (int) ((u + 1F) * ChunkMesher.UV_SCALE + 0.5F);
        int pv = (int) ((v + 1F) * ChunkMesher.UV_SCALE + 0.5F);
        int c = Math.clamp(Math.round(brightness * 255F), 0, 255);
        out[i++] = px | (py << 16);
        out[i++] = pz | (pu << 16);
        out[i++] = pv | (layer << 16);
        out[i++] = c | (c << 8) | (c << 16);
        return i;
    }
}
