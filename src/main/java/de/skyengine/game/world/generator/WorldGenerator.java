package de.skyengine.game.world.generator;

import de.skyengine.game.world.block.Blocks;
import de.skyengine.game.world.block.Tints;
import de.skyengine.game.world.chunk.Chunk;
import de.skyengine.game.world.chunk.ChunkSection;
import de.skyengine.game.world.generator.biome.Biome;
import de.skyengine.game.world.generator.biome.Biomes;
import de.skyengine.game.world.lod.LodDataSource;
import de.skyengine.game.world.lod.LodVolumeRequest;
import de.skyengine.game.world.lod.LodVolumeWriter;
import de.skyengine.game.world.lod.LodVoxel;

public abstract class WorldGenerator {

    public record LodSurfaces(long ground, long surface) {}

    protected final int seed;

    public WorldGenerator(int seed) {
        this.seed = seed;
    }

    public abstract int sampleHeight(int x, int z);
    public abstract void generate(Chunk chunk);

    /**
     * Oberflächen-Sample fürs LOD: oberster sichtbarer Block + dessen Höhe, gepackt via
     * {@link LodDataSource#pack}. Pure Funktion, threadsicher. Default: Gras auf
     * {@link #sampleHeight}; Generatoren mit Wasser/Material-Zonen überschreiben das.
     */
    public long sampleSurface(int x, int z) {
        return LodDataSource.pack(Blocks.GRASS_BLOCK, this.sampleHeight(x, z));
    }

    /**
     * Boden-Sample fürs LOD: wie {@link #sampleSurface}, aber ohne Wasser — liefert auch
     * unter Wasser den festen Boden. Default: identisch zur Oberfläche (Generatoren ohne
     * Wasser); Generatoren mit Wasserspiegel überschreiben das.
     */
    public long sampleGroundSurface(int x, int z) {
        return this.sampleSurface(x, z);
    }

    /** Gemeinsames LOD-Sample; teure Generatoren können beide Oberflächen in einem Pass liefern. */
    public LodSurfaces sampleLodSurfaces(int x, int z) {
        return new LodSurfaces(this.sampleGroundSurface(x, z), this.sampleSurface(x, z));
    }

    /**
     * Befuellt die kanonischen 32x32-L0-Oberflaechen eines Chunks. Der Index ist immer
     * {@code localZ * 32 + localX}; beide Zielarrays muessen mindestens 1024 Eintraege haben.
     *
     * <p>Der Default erhaelt jeden bestehenden und zukuenftigen Generator korrekt, indem er
     * {@link #sampleLodSurfaces} pro Spalte aufruft. Generatoren mit gemeinsam nutzbaren
     * Klima-/Noise-Grids koennen die Methode ueberschreiben und den Chunk in einem Bulk-Pass
     * berechnen, ohne den LOD-Vertrag oder {@link #generate} aufzurufen.
     */
    public void fillLodSurfaces(int chunkX, int chunkZ, long[] ground, long[] surface) {
        requireLodSurfaceCapacity(ground, surface);
        int baseX = chunkX << ChunkSection.SHIFT;
        int baseZ = chunkZ << ChunkSection.SHIFT;
        for (int z = 0; z < ChunkSection.SIZE; z++) {
            for (int x = 0; x < ChunkSection.SIZE; x++) {
                LodSurfaces sampled = this.sampleLodSurfaces(baseX + x, baseZ + z);
                int index = z * ChunkSection.SIZE + x;
                ground[index] = sampled.ground;
                surface[index] = sampled.surface;
            }
        }
    }

    /**
     * Analytischer volumetrischer LOD-Pfad. Er erzeugt einen 32³-Knoten ohne einen echten
     * {@link Chunk} und bildet damit die schnelle vorlaeufige Quelle der Hierarchie. Der Default
     * extrudiert die beiden vorhandenen Oberflaechen-Samples; Generatoren mit 3D-Dichte,
     * Ueberhaengen oder Hoehlen koennen diese Methode ueberschreiben und echte Volumenzellen
     * schreiben. Gespeicherte oder live geladene Kindknoten ersetzen diese Daten spaeter
     * kanonisch.
     */
    public void fillLodVolume(LodVolumeRequest request, LodVolumeWriter writer) {
        if (request == null || writer == null) throw new IllegalArgumentException("LOD-Request/Writer fehlt");
        int cell = request.cellSize();
        int originX = request.originX(), originY = request.originY(), originZ = request.originZ();
        int bottomState = this.lodWorldBottomState();
        for (int z = 0; z < ChunkSection.SIZE; z++) {
            int worldZ = originZ + z * cell + cell / 2;
            for (int x = 0; x < ChunkSection.SIZE; x++) {
                int worldX = originX + x * cell + cell / 2;
                LodSurfaces surfaces = this.sampleLodSurfaces(worldX, worldZ);
                int groundState = LodDataSource.block(surfaces.ground());
                int groundTop = LodDataSource.height(surfaces.ground()) + 1;
                int surfaceState = LodDataSource.block(surfaces.surface());
                int surfaceTop = Math.max(groundTop, LodDataSource.height(surfaces.surface()) + 1);
                for (int y = 0; y < ChunkSection.SIZE; y++) {
                    int minY = originY + y * cell;
                    int maxY = minY + cell;
                    int filledTop = Math.min(maxY, surfaceTop);
                    int filled = Math.max(0, filledTop - minY);
                    if (filled == 0 || maxY <= 0) continue;
                    int state;
                    int importance;
                    if (minY < 1 && bottomState != Blocks.AIR) {
                        state = bottomState;
                        importance = 63;
                    } else if (maxY > groundTop && surfaceState != Blocks.AIR) {
                        state = surfaceState;
                        importance = 12;
                    } else {
                        state = groundState;
                        importance = 4;
                    }
                    if (state == Blocks.AIR) continue;
                    int coverage = Math.clamp((filled * 255 + cell / 2) / cell, 1, 255);
                    int sky = maxY >= surfaceTop ? 15 : 0;
                    writer.set(x, y, z, LodVoxel.pack(state, sky, 0, 0, 0, coverage,
                            LodVoxel.PROVENANCE_ANALYTIC, importance));
                }
            }
        }
    }

    /** Gemeinsame Vertragspruefung fuer optimierte Generator-Overrides. */
    protected static void requireLodSurfaceCapacity(long[] ground, long[] surface) {
        int required = ChunkSection.SIZE * ChunkSection.SIZE;
        if (ground == null || surface == null || ground.length < required || surface.length < required) {
            throw new IllegalArgumentException("LOD-Oberflaechenpuffer muessen mindestens "
                    + required + " Eintraege besitzen");
        }
    }

    /** Unterste Materialschicht der Generatorwelt; AIR bedeutet, dass kein Weltboden existiert. */
    public int lodWorldBottomState() {
        return Blocks.AIR;
    }

    /**
     * Biom an Weltposition — pures Sampling, threadsicher. Default: Ebene (Generatoren ohne
     * Biome, z.B. V1); Biome-Generatoren ueberschreiben das.
     */
    public Biome biomeAt(int x, int z) {
        return Biomes.PLAINS;
    }

    /**
     * Echte Terrainoberkante (oberster Solid-Block) — Basis fuer Feature-Platzierung.
     * Default: {@link #sampleHeight}; Generatoren mit 3D-Dichte ueberschreiben das, weil
     * ihre reale Oberflaeche von der 2D-Hoehe abweichen kann.
     */
    public int surfaceSolidHeight(int x, int z) {
        return this.sampleHeight(x, z);
    }

    /**
     * Gras-Farbe an Weltposition (fuers LOD; L0 nutzt die Tint-Grids aus generate()).
     * Default: fester Platzhalter — Generatoren mit Biomen ueberschreiben das.
     */
    public int grassTintAt(int x, int z) {
        return Tints.GRASS;
    }

    /** Laub-Farbe an Weltposition, s. {@link #grassTintAt}. */
    public int foliageTintAt(int x, int z) {
        return Tints.FOLIAGE;
    }

    /**
     * Befüllt die 33x33-Tint-Eck-Grids des Chunks neu — Persistenz-Load-Pfad, wenn keine
     * Grids gespeichert sind. MUSS derselbe Codepfad wie in {@link #generate} sein (V2:
     * geglättetes Bilerp, NICHT {@link #grassTintAt} — sonst Farb-Nähte zu frisch
     * generierten Nachbarn). Default: keine Grids (Generatoren ohne Biome-Tints).
     */
    public void fillTintCorners(Chunk chunk) {}

    public int getSeed() {
        return seed;
    }
}
