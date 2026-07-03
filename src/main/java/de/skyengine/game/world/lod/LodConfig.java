package de.skyengine.game.world.lod;

/**
 * Unveränderliche Ring-Konfiguration einer Settings-Epoche: {@code renderDistance} = Ende
 * von L0 (echte Chunks), {@code rings} = äußere Ringgrenzen in Chunks für L1..Ln
 * (aus {@code GameSettings.lodRings}, z.B. {32, 64, 128} bei rd 16 → L1 16–32, L2 32–64,
 * L3 64–128). Wird vom {@link LodManager} erzeugt und den Mesh-Jobs mitgegeben — Manager
 * und Mesher rechnen so garantiert mit derselben Level-Zuordnung (keine Sync-Logik).
 */
public record LodConfig(int renderDistance, int[] rings) {

    /**
     * LOD-Level (1..n) für eine Distanz in Blöcken. Jenseits des letzten Rings wird aufs
     * letzte Level geklemmt (solche Regionen sind nie desired, Nachbar-Abfragen des Meshers
     * bleiben aber konsistent).
     */
    public int levelAt(double distBlocks) {
        for (int i = 0; i < this.rings.length - 1; i++) {
            if (distBlocks < this.rings[i] * 32.0) return i + 1;
        }
        return this.rings.length;
    }

    /** Zellgröße eines Levels in Blöcken (2^L; sanitize begrenzt auf max. 5 Level = 32). */
    public int cellSize(int level) {
        return 1 << level;
    }

    /** Äußerer Rand des letzten LOD-Rings in Blöcken. */
    public double outerRadiusBlocks() {
        return this.rings[this.rings.length - 1] * 32.0;
    }

    /**
     * Radius in Blöcken, innerhalb dessen LOD-Zellen übersprungen werden: der äußerste
     * geladene Chunk-Ring wird nie gemesht (8-Nachbarn-Bedingung) und die Mesh-Grenze ist
     * ausgefranst → rd-2, plus 16 Toleranz für die Chunk-Quantisierung der Spielerposition.
     * Überlappung mit echtem Terrain ist unkritisch (Sections zeichnen zuerst und gewinnen
     * den Depth-Test), Lücken zeigen dagegen Himmel an Hang-Silhouetten.
     */
    public float clipRadius() {
        return (this.renderDistance - 2) * 32.0F - 16.0F;
    }
}
