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

    /** true, wenn der Mesher die mehrschichtige Spaltendarstellung verwenden soll. */
    default boolean hasColumns() {
        return false;
    }

    /** Nur normale Generatorwelten besitzen eine garantierte Weltboden-Schicht bei Y=0. */
    default boolean hasWorldBottom() {
        return true;
    }

    /**
     * Bis zu vier sichtbare Solid-/Fluid-Intervalle der global ausgerichteten Zelle.
     * Der Default erhält die bisherige Einflächenquelle für Werkzeuge und Tests.
     */
    default LodColumn sampleColumn(int x, int z, int size) {
        long surface = this.sampleSurface(x, z, size);
        int block = block(surface);
        if (block == 0) return LodColumn.EMPTY;
        int top = height(surface) + 1;
        return new LodColumn(new long[]{LodColumn.pack(block, 0, Math.max(1, top), 0)});
    }

    /**
     * Fuellt ein zusammenhaengendes Spaltenfenster. Implementierungen mit chunkweisem Cache
     * koennen dadurch ihre Quell-Chunks einmal pro Fenster statt einmal pro Zelle aufloesen.
     */
    default void sampleColumns(int startX, int startZ, int size, int width, int height,
                               LodColumn[] target, int targetOffset, int targetStride) {
        for (int z = 0; z < height; z++) {
            int row = targetOffset + z * targetStride;
            for (int x = 0; x < width; x++) {
                target[row + x] = this.sampleColumn(startX + x * size, startZ + z * size, size);
            }
        }
    }

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
