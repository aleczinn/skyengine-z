package de.skyengine.game.world.lod;

import de.skyengine.game.world.block.Tints;

/**
 * Abstrahierte Oberflächen-Quelle fürs LOD: liefert pro Zelle den obersten sichtbaren Block
 * und dessen Höhe. Verdrahtet ist {@link WorldLodDataSource} (nah: echte Chunkdaten, fern:
 * pure Generator-Funktion — s. World.init); später können gespeicherte Welten und Strukturen
 * dieselbe Schnittstelle bedienen, ohne dass Mesher/Manager sich ändern.
 *
 * <p>Implementierungen MÜSSEN threadsicher und deterministisch sein: die Worker rufen sie
 * parallel auf, und benachbarte Regionen sampeln dieselben Rand-Zellen erneut — nur wenn
 * beide dasselbe Ergebnis sehen, schließen die Wände an den Regionsgrenzen lückenlos.
 */
public interface LodDataSource {

    /**
     * Oberflächen-Sample für eine size×size-Zelle mit Ursprung (x,z) in Weltkoordinaten
     * (size = Zellgröße in Blöcken, Ursprung size-aligned). Ergebnis gepackt via
     * {@link #pack}; auslesen mit {@link #block(long)} / {@link #height(long)}.
     */
    long sampleSurface(int x, int z, int size);

    /**
     * Boden-Sample einer Zelle: wie {@link #sampleSurface}, aber ohne Wasser — liefert auch
     * unter Wasser den festen Boden (fuer das Terrain unter LOD-Wasserflaechen). Default:
     * identisch zur Oberflaeche (Quellen ohne Wasser-Kenntnis).
     */
    default long sampleGround(int x, int z, int size) {
        return this.sampleSurface(x, z, size);
    }

    /** Biome-Grasfarbe an Weltposition (fuer GRASS-getintete LOD-Zellen). Default: Platzhalter. */
    default int grassTintAt(int x, int z) {
        return Tints.GRASS;
    }

    /** Biome-Laubfarbe an Weltposition (fuer FOLIAGE-getintete LOD-Zellen). */
    default int foliageTintAt(int x, int z) {
        return Tints.FOLIAGE;
    }

    static long pack(int blockId, int height) {
        return ((long) blockId << 32) | (height & 0xFFFFFFFFL);
    }

    static int block(long sample) {
        return (int) (sample >> 32);
    }

    static int height(long sample) {
        return (int) sample;
    }
}
