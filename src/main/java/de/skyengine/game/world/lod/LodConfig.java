package de.skyengine.game.world.lod;

/**
 * Unveränderliche Ring-Konfiguration einer Settings-Epoche, rein formelbasiert aus den zwei
 * Config-Werten {@code renderDistance} (RD, Ende von L0 = echte Chunks) und
 * {@code lodMaxDistance} (äußerste LOD-Reichweite):
 *
 * <pre>
 * maxLevel      = clamp(ceil(log2(lodMaxDistance / RD)), 1, 5)   // Stride 2^5 = 32 = Formatgrenze
 * levelAt(dist) = clamp(floor(log2(dist / RD)) + 1, 1, maxLevel)
 *                 (+1 im Fern-Band, s. FAR_BAND_FACTOR, Deckel 5)
 * </pre>
 *
 * Beispiel RD=24/lodMax=256: L1 24–48 (Stride 2), L2 48–96 (4), L3 96–192 (8), L4 192–256 (16).
 * RD oder lodMaxDistance ändern ⇒ die Level-Anzahl ergibt sich automatisch.
 *
 * <p>Wird vom {@link LodManager} erzeugt und den Mesh-Jobs mitgegeben — Manager und Mesher
 * rechnen so garantiert mit derselben Level-Zuordnung (keine Sync-Logik).
 */
public record LodConfig(int renderDistance, int lodMaxDistance, int maxLevel) {

    public static LodConfig of(int renderDistance, int lodMaxDistance) {
        int ratio = (int) Math.ceil((double) lodMaxDistance / renderDistance);
        int maxLevel = ratio <= 1 ? 1 : 32 - Integer.numberOfLeadingZeros(ratio - 1); // ceil(log2)
        return new LodConfig(renderDistance, lodMaxDistance, Math.clamp(maxLevel, 1, 5));
    }

    /* Anteil des Außenradius, ab dem das äußerste Band eine Stufe gröber sampelt (+1 Level):
       halbiert dort die Zellen pro Achse (größter LOD-Quad-Hebel laut Zensus). 0.75 hält die
       Vergröberung im hintersten Viertel, wo Distanz-Fog und Pixeldichte sie kaschieren. */
    private static final double FAR_BAND_FACTOR = 0.75;

    /** LOD-Level (1..maxEffectiveLevel) für eine Distanz in Blöcken; innen wird auf L1 geklemmt. */
    public int levelAt(double distBlocks) {
        int n = (int) (distBlocks / (this.renderDistance * 32.0));
        if (n < 1) return 1;
        int level = Math.min(32 - Integer.numberOfLeadingZeros(n), this.maxLevel); // floor(log2)+1
        /* Fern-Band nur im äußersten (maxLevel-)Ring — die Bedingung level == maxLevel hält
           die Zuordnung monoton über die Distanz (kein Band-Sprung bei krummen Ratios). */
        if (level == this.maxLevel && distBlocks >= this.outerRadiusBlocks() * FAR_BAND_FACTOR) {
            level = Math.min(level + 1, 5);
        }
        return level;
    }

    /** Höchstes real vergebenes Level inkl. Fern-Band (für Array-Dimensionierung pro Level). */
    public int maxEffectiveLevel() {
        return Math.min(this.maxLevel + 1, 5);
    }

    /** Zellgröße (Stride) eines Levels in Blöcken (2^L, max 32). */
    public int cellSize(int level) {
        return 1 << level;
    }

    /** Äußerer LOD-Rand in Blöcken (Desired-Deckel; die Level-Formel selbst bleibt pur). */
    public double outerRadiusBlocks() {
        return this.lodMaxDistance * 32.0;
    }
}
