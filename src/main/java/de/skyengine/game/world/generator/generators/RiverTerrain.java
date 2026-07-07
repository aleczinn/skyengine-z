package de.skyengine.game.world.generator.generators;

/**
 * Terrain-Hooks des Fluss-Netzes: {@link RiverNetwork} ist gegen dieses Interface geschrieben,
 * damit mehrere Generatoren (V2, V3) dasselbe Quelle-zu-Muendung-Netz nutzen koennen.
 *
 * <p>Alle Methoden MUESSEN pure Funktionen von (Seed, Position) sein — der Trace fragt sie
 * chunk-unabhaengig und parallel ab (s. {@link RiverNetwork}-Klassendoku). Die Seen duerfen
 * dabei nicht von Fluessen abhaengen (Cache-Rekursion!).
 */
interface RiverTerrain {

    /**
     * Leitfeld des Fluss-Traces: glatte Grundhoehe plus Gebirgs-Penalty, bewusst OHNE die
     * hochfrequenten Oktaven — die Falllinie soll grossraeumig fallen, nicht lokal zittern.
     */
    float riverGuide(int x, int z);

    /**
     * Traeger des Spiegel-Profils: folgt dem echten Terrain bis auf die kleinen Detail-Oktaven
     * — nur so liegt das monotone Spiegel-Profil verlaesslich UNTER der Wiese.
     */
    float riverCarrier(int x, int z);

    /** Kontinentalitaet (glatt) — Quell-Gate des Fluss-Netzes. */
    float continentalnessAt(int x, int z);

    /** Liegt (x, z) im Wasser eines Worley-Sees? Quell-Gate des Fluss-Traces. */
    boolean insideLake(int x, int z);

    /**
     * Der See, dessen Wasser (x, z) naeher als {@code margin} kommt — Muendungs-Erkennung
     * des Traces (grosszuegiger Radius-Test OHNE Ufer-Noise, 3x3-Nachbarzellen pruefen).
     */
    Lake lakeNear(int x, int z, int margin);
}
