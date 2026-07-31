package de.skyengine.game.world.chunk.debug;

import de.skyengine.core.file.Files;
import de.skyengine.core.settings.GameSettings;
import de.skyengine.game.world.block.Blocks;
import de.skyengine.game.world.chunk.Chunk;
import de.skyengine.game.world.chunk.ChunkMesher;
import de.skyengine.game.world.chunk.ChunkStatus;
import de.skyengine.game.world.generator.generators.AlphaWorldGeneratorV2;
import de.skyengine.game.world.light.LightEngine;

import java.io.File;
import java.util.Locale;

/**
 * Standalone-Werkzeug (eigene main, kein GL/Engine-Start, Muster {@code LightProbe}):
 * deterministischer Zensus des Section-Meshers. Generiert 3×3 Chunks (Seed 123), lässt die
 * {@link LightEngine} in fester Reihenfolge darüber laufen und mesht alle 9 Chunks — die
 * mittleren mit echten Nachbarn und Diagonalen, die Randchunks mit null-Nachbarn (deckt den
 * Fallback-Pfad des NeighborSamplers mit ab). Ausgegeben werden Quad-Zähler je Layer und ein
 * FNV-1a-Hash über alle Vertex-Daten.
 *
 * <p>Zweck: Bit-Identitäts-Beweis bei Mesher-Umbauten (Sampling-Fast-Path, Greedy, AO) —
 * gleiche Hash-Zeile vor/nach der Änderung = identische Geometrie bis ins letzte Bit.
 * Ein stiller Greedy-Regress fällt zusätzlich über explodierende Quad-Zähler auf.</p>
 */
public final class MesherCensus {

    private static final int SEED = 123;
    /* Zentrum = der auch von LightProbe genutzte Terrain-Chunk (Wasser, Bäume, Höhlen). */
    private static final int CENTER_X = 3, CENTER_Z = -7;

    private MesherCensus() {
    }

    public static void main(String[] args) {
        Blocks.bootstrap(new File(Files.RESOURCES_PATH, "game/blocks"));

        /* Settings pinnen: der Hash darf nicht von der options.json des Rechners abhängen.
           AO an + Laub HIGH = die geometriereichsten Pfade (per-Vertex-AO, kein Laub-Culling). */
        GameSettings settings = GameSettings.get();
        settings.ambientOcclusion = true;
        settings.leavesQuality = GameSettings.LeavesQuality.HIGH;

        /* 3×3-Feld generieren (Index = (dz+1)*3 + (dx+1)). */
        Chunk[] grid = new Chunk[9];
        AlphaWorldGeneratorV2 generator = new AlphaWorldGeneratorV2(SEED);
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                Chunk chunk = new Chunk(CENTER_X + dx, CENTER_Z + dz);
                generator.generate(chunk);
                grid[(dz + 1) * 3 + (dx + 1)] = chunk;
            }
        }

        /* Licht in fester Reihenfolge: erst alle initial (mit vorhandenen Nachbarn), dann der
           Randaustausch — Reihenfolge ist Teil des Determinismus-Vertrags dieses Zensus. */
        LightEngine engine = new LightEngine();
        for (int i = 0; i < 9; i++) {
            engine.lightInitial(grid[i], at(grid, i, 0, -1), at(grid, i, 0, 1),
                    at(grid, i, -1, 0), at(grid, i, 1, 0), diagonals(grid, i));
            grid[i].status = ChunkStatus.LIT;
        }
        for (int i = 0; i < 9; i++) {
            engine.exchangeBorders(grid[i], at(grid, i, 0, -1), at(grid, i, 0, 1),
                    at(grid, i, -1, 0), at(grid, i, 1, 0), diagonals(grid, i));
        }

        /* Alle 9 Chunks meshen, Hash + Quad-Zähler einsammeln. */
        ChunkMesher mesher = new ChunkMesher();
        long hash = 0xcbf29ce484222325L; // FNV-1a Offset-Basis
        long[] quads = new long[4];      // opaque, cutout, translucent, detail
        int sections = 0;
        for (int i = 0; i < 9; i++) {
            for (int s = 0; s < Chunk.SECTIONS; s++) {
                ChunkMesher.MeshData data = mesher.mesh(grid[i], s, at(grid, i, 0, -1),
                        at(grid, i, 0, 1), at(grid, i, -1, 0), at(grid, i, 1, 0), diagonals(grid, i));
                if (data == null) continue;
                sections++;
                hash = fnv(hash, data.opaque);
                hash = fnv(hash, data.cutout);
                hash = fnv(hash, data.translucent);
                hash = fnv(hash, data.detail);
                quads[0] += quadCount(data.opaque);
                quads[1] += quadCount(data.cutout);
                quads[2] += quadCount(data.translucent);
                quads[3] += quadCount(data.detail);
            }
        }

        System.out.println("Sections mit Geometrie: " + sections);
        System.out.println("Quads: opaque=" + quads[0] + " cutout=" + quads[1]
                + " translucent=" + quads[2] + " detail=" + quads[3]);
        System.out.println(String.format(Locale.ROOT, "MESH %016x", hash));
        System.out.println("MESH OK");
    }

    /** Nachbar (dx,dz) im 3×3-Feld oder null (Randchunk → NeighborSampler-Fallback). */
    private static Chunk at(Chunk[] grid, int index, int dx, int dz) {
        int gx = index % 3 + dx, gz = index / 3 + dz;
        return gx < 0 || gx > 2 || gz < 0 || gz > 2 ? null : grid[gz * 3 + gx];
    }

    /** Diagonalen in der ChunkManager-Reihenfolge NW, NE, SW, SE. */
    private static Chunk[] diagonals(Chunk[] grid, int index) {
        return new Chunk[]{at(grid, index, -1, -1), at(grid, index, 1, -1),
                at(grid, index, -1, 1), at(grid, index, 1, 1)};
    }

    private static long quadCount(int[] data) {
        return data == null ? 0 : data.length / (4L * ChunkMesher.VERTEX_SIZE);
    }

    /** FNV-1a 64 über die Ints (null = eigener Marker, damit leer ≠ fehlend). */
    private static long fnv(long hash, int[] data) {
        if (data == null) return (hash ^ 0x9E3779B97F4A7C15L) * 0x100000001b3L;
        hash = (hash ^ data.length) * 0x100000001b3L;
        for (int v : data) {
            hash = (hash ^ (v & 0xFFFFFFFFL)) * 0x100000001b3L;
        }
        return hash;
    }
}
