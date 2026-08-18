package de.skyengine.game.world.generator.feature;

/**
 * Ein dekoratives Welt-Feature (z.B. ein Baum), das im Feature-Pass nach der
 * Terrain-Generierung platziert wird.
 *
 * <p><b>Vertrag (Scheiben-Modell):</b> Platzierung und Form MÜSSEN eine pure Funktion aus
 * {@link FeatureContext#random()} und dem puren Generator-Sampling
 * ({@link FeatureContext#surfaceHeight}/{@link FeatureContext#surfaceBlock}) sein. Niemals
 * Chunk-Blockdaten lesen, um Platzierung oder Form zu entscheiden — dasselbe Feature wird
 * von jedem Chunk, den es schneidet, unabhängig neu berechnet und muss überall identisch
 * ausfallen, sonst divergieren die Scheiben. Schreib-Filter wie
 * {@link FeatureContext#setIfAir} sind erlaubt (jede Zelle hat genau einen Besitzer-Chunk,
 * die Anwendungs-Reihenfolge ist fix).
 *
 * <p>Maximaler Overreach: 1 Chunk (32 Blöcke) über die Quell-Chunk-Grenze hinaus — weiter
 * reicht der 3×3-Dekorations-Radius nicht, entferntere Scheiben würden fehlen.
 */
public interface Feature {

    /** Platziert alle Instanzen dieses Features für den Quell-Chunk des Placers. */
    default int cacheVersion() {
        return 1;
    }

    void place(FeatureContext placer);
}
