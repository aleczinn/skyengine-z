package de.skyengine.game.world.lod;

import de.skyengine.game.world.block.Tints;
import de.skyengine.game.world.block.Blocks;
import de.skyengine.game.world.chunk.Chunk;

import java.util.Arrays;

/**
 * Abstrahierte Oberflächen-Quelle fürs LOD: liefert pro Zelle den obersten sichtbaren Block
 * und dessen Höhe. Verdrahtet ist ausschließlich {@link PersistentLodDataSource} (s. Dimension.init).
 * Sie entscheidet je QUELLCHUNK und NICHT je Level, in dieser Reihenfolge:
 * <ol>
 *   <li><b>RAM-Cache</b> — zaehlt nur als Treffer, wenn dort ein Level <b>&le;</b> dem
 *       angeforderten liegt ({@code materializeLevel} leitet ausschliesslich zu GROEBEREN
 *       Leveln ab, nie zurueck zu feineren).</li>
 *   <li><b>LOD-Disk-Cache</b> — gelesen NUR, wenn ueberhaupt kein RAM-Eintrag existiert und der
 *       Chunk nicht invalidiert ist. Er folgt also nicht auf einen unbrauchbaren RAM-Treffer:
 *       liegt im RAM nur ein grobes Level und wird ein feineres verlangt, wird er UEBERSPRUNGEN
 *       und direkt ueber 3./4./5. neu gebaut. Invalidiert wird beim Speichern eines Chunks,
 *       also bei jeder Spieleraenderung.</li>
 *   <li><b>residenter Chunk</b> (&ge; DECORATED, bewusst ohne Lock — transiente Fehler remeshen
 *       sich weg)</li>
 *   <li><b>Savegame-Snapshot</b></li>
 *   <li><b>Generator</b> + Feature-Pass</li>
 * </ol>
 * Einen „generatorreinen" Fernring gibt es deshalb nicht — auch L3/L4 lesen residente Chunkdaten,
 * wenn welche in Reichweite liegen. {@code WorldLodDataSource} und {@code StorageLodDataSource}
 * haben seit diesem Umbau keinen Aufrufer mehr und beschreiben NICHT mehr den verdrahteten Pfad.
 *
 * <p>Implementierungen MÜSSEN threadsicher und deterministisch sein: die Worker rufen sie
 * parallel auf, und benachbarte Regionen sampeln dieselben Rand-Zellen erneut — nur wenn
 * beide dasselbe Ergebnis sehen, schließen die Wände an den Regionsgrenzen lückenlos.
 */
public interface LodDataSource {

    /**
     * Kurzlebiger Vollblock-Sampler fuer L0/LOD-Uebergaenge. Anders als {@link LodColumn}
     * bewahrt er jede Hoehle und jedes Fluid in einer 512 Block hohen Spalte. Der boolesche
     * Rueckgabewert ist true, wenn die Spalte aus einem aktuell residenten Chunk stammt.
     * Damit validiert der Mesher, dass ein als sichtbar markierter L0-Nachbar noch zum
     * Clip-Snapshot gehoert; Backing-Spalten duerfen dagegen aus Savegame/Generator stammen.
     */
    interface ExactColumnSampler extends AutoCloseable {
        boolean sampleColumn(int x, int z, int[] target);

        /**
         * Liefert fuer die durch {@code x/z} bezeichnete Randspalte die vom sichtbaren
         * L0-Chunk-Mesh tatsaechlich emittierten Faces. {@code face} ist die L0-seitige
         * Blickrichtung (2=N, 3=S, 4=W, 5=E), jeder der 16 ints enthaelt die 32 lokalen
         * Y-Bits einer Section. false bedeutet: kein zum Clip-Vertrag passender L0-Upload.
         */
        default boolean sampleRenderedBoundaryFaces(int x, int z, int face, int[] target) {
            Arrays.fill(target, 0);
            /* Kompakte Test-/Werkzeugquellen besitzen kein separates L0-Mesh: dort gilt
               folgerichtig "kein Face bereits gerendert", der Stitcher rekonstruiert es. */
            return true;
        }

        @Override
        default void close() {}
    }

    /**
     * Oeffnet einen joblokalen exakten Sampler. Der Default expandiert die vorhandene
     * Spaltendarstellung und haelt damit kleine Test-/Werkzeugquellen kompatibel. Persistente
     * Welten ueberschreiben den Pfad mit echten Chunkdaten.
     */
    default ExactColumnSampler openExactColumnSampler() {
        return (x, z, target) -> {
            if (target.length < Chunk.HEIGHT) {
                throw new IllegalArgumentException("Exakte LOD-Spalte ist zu klein: " + target.length);
            }
            Arrays.fill(target, 0, Chunk.HEIGHT, Blocks.AIR);
            LodColumn column = this.sampleColumn(x, z, 1);
            for (int i = 0; i < column.size(); i++) {
                long interval = column.interval(i);
                Arrays.fill(target, LodColumn.minY(interval), LodColumn.maxY(interval),
                        LodColumn.state(interval));
            }
            return true;
        };
    }

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
