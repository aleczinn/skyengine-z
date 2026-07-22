package de.skyengine.game.world.generator.generators;

import de.skyengine.game.world.block.Blocks;
import de.skyengine.game.world.chunk.Chunk;
import de.skyengine.game.world.generator.WorldGenerator;
import de.skyengine.game.world.lod.LodDataSource;

/**
 * Leer-Generator für importierte Welten (worldType "imported"): alle Chunks kommen aus den
 * Region-Dateien, Generierung liefert bewusst nichts. Fern-LOD jenseits der gespeicherten
 * Chunks existiert damit nicht (sampleSurface = Luft) — bewusste, simpelste Lösung.
 */
public final class VoidWorldGenerator extends WorldGenerator {

    public VoidWorldGenerator(int seed) {
        super(seed);
    }

    @Override
    public int sampleHeight(int x, int z) {
        return 0;
    }

    @Override
    public void generate(Chunk chunk) {
        /* Leer — ein Chunk ohne Region-Eintrag bleibt Luft (Rand einer importierten Welt). */
    }

    @Override
    public long sampleSurface(int x, int z) {
        return LodDataSource.pack(Blocks.AIR, 0);
    }
}
