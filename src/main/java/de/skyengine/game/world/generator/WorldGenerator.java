package de.skyengine.game.world.generator;

import de.skyengine.game.world.block.Blocks;
import de.skyengine.game.world.block.Tints;
import de.skyengine.game.world.chunk.Chunk;
import de.skyengine.game.world.generator.biome.Biome;
import de.skyengine.game.world.generator.biome.Biomes;
import de.skyengine.game.world.lod.LodDataSource;

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
