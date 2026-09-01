package de.skyengine.game.world.chunk.debug;

import de.skyengine.core.file.Files;
import de.skyengine.core.settings.GameSettings;
import de.skyengine.game.world.block.Blocks;
import de.skyengine.game.world.chunk.Chunk;
import de.skyengine.game.world.chunk.ChunkMesher;
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

    /** Intentional Grass-Composite baseline; update only together with a reviewed mesh change. */
    private static final long EXPECTED_MESH_HASH = 0xb100fe64b6cb9abdL;

    private MesherCensus() {
    }

    public static void main(String[] args) {
        Blocks.bootstrap(new File(Files.RESOURCES_PATH, "game/blocks"));

        /* Settings pinnen: der Hash darf nicht von der options.json des Rechners abhängen.
           AO an + Laub HIGH = die geometriereichsten Pfade (per-Vertex-AO, kein Laub-Culling). */
        GameSettings settings = GameSettings.get();
        settings.ambientOcclusion = true;
        settings.leavesQuality = GameSettings.LeavesQuality.HIGH;

        /* Fixture erzeugt und beleuchtet das deterministische 3×3-Feld. */
        MesherFixture.Grid fixture = MesherFixture.generated();
        Chunk[] grid = fixture.chunks();

        /* Alle 9 Chunks meshen, Hash + Quad-Zähler einsammeln. */
        ChunkMesher mesher = new ChunkMesher();
        long hash = 0xcbf29ce484222325L; // FNV-1a Offset-Basis
        long[] quads = new long[4];      // opaque, cutout, translucent, detail
        long[] compactQuads = new long[3];
        long[] compactBytes = new long[3];
        long fullCubeFaces = 0, cornerFaces = 0, rejectedShading = 0,
                rejectedMaterial = 0, rejectedState = 0, overlayFallbackFaces = 0;
        long compositeGrassFaces = 0, compositeGrassQuads = 0, compositeGrassBytes = 0;
        int sections = 0;
        for (int i = 0; i < 9; i++) {
            for (int s = 0; s < Chunk.SECTIONS; s++) {
                ChunkMesher.MeshData data = mesher.mesh(grid[i], s, fixture.at(i, 0, -1),
                        fixture.at(i, 0, 1), fixture.at(i, -1, 0), fixture.at(i, 1, 0),
                        fixture.diagonals(i));
                if (data == null) continue;
                sections++;
                hash = fnv(hash, data.opaque);
                hash = fnv(hash, data.cutout);
                hash = fnv(hash, data.translucent);
                hash = fnv(hash, data.detail);
                if (data.compactGeometry != null) for (int mode = 0; mode < 3; mode++) {
                    hash = fnv(hash, data.compactGeometry[mode]);
                    hash = fnv(hash, data.compactShading[mode]);
                    compactQuads[mode] += data.compactGeometry[mode] == null ? 0
                            : data.compactGeometry[mode].length / 2L;
                }
                if (data.stats != null) {
                    fullCubeFaces += data.stats.fullCubeFacesBeforeGreedy();
                    cornerFaces += data.stats.cornerShadingFaces();
                    rejectedShading += data.stats.mergeRejectedByShading();
                    rejectedMaterial += data.stats.mergeRejectedByMaterial();
                    rejectedState += data.stats.mergeRejectedByState();
                    overlayFallbackFaces += data.stats.overlayFallbackFaces();
                    compositeGrassFaces += data.stats.compositeGrassFacesBeforeGreedy();
                    compositeGrassQuads += data.stats.compositeGrassQuadsAfterGreedy();
                    compositeGrassBytes += data.stats.compositeGrassBytes();
                    compactBytes[0] += data.stats.standardBytes();
                    compactBytes[1] += data.stats.uniformBytes();
                    compactBytes[2] += data.stats.cornerBytes();
                }
                quads[0] += quadCount(data.opaque);
                quads[1] += quadCount(data.cutout);
                quads[2] += quadCount(data.translucent);
                quads[3] += quadCount(data.detail);
            }
        }

        System.out.println("Sections mit Geometrie: " + sections);
        System.out.println("Quads: opaque=" + quads[0] + " cutout=" + quads[1]
                + " translucent=" + quads[2] + " detail=" + quads[3]);
        System.out.println("Compact: standard=" + compactQuads[0] + " uniform=" + compactQuads[1]
                + " corner=" + compactQuads[2] + " bytes=" + compactBytes[0] + "/"
                + compactBytes[1] + "/" + compactBytes[2]);
        System.out.println("FullCube: faces=" + fullCubeFaces + " corner=" + cornerFaces
                + " rejected(shading/material/state)=" + rejectedShading + "/"
                + rejectedMaterial + "/" + rejectedState
                + " overlayFallback=" + overlayFallbackFaces);
        System.out.println("GrassComposite: faces=" + compositeGrassFaces + " quads="
                + compositeGrassQuads + " bytes=" + compositeGrassBytes);
        System.out.println(String.format(Locale.ROOT, "MESH %016x", hash));
        if (hash != EXPECTED_MESH_HASH) {
            throw new AssertionError(String.format(Locale.ROOT,
                    "Mesh hash mismatch: expected %016x, got %016x", EXPECTED_MESH_HASH, hash));
        }
        System.out.println("MESH OK");
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
