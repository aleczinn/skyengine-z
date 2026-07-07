package de.skyengine.game.world.generator.biome;

import de.skyengine.game.world.generator.climate.ClimateV3;

/**
 * Terrain-Profil eines (Inland-)Bioms fuer den V3-Generator: ein Zielpunkt im 5D-Klimaraum
 * plus die Terrain-Parameter, die dort gelten. {@link BiomeWeights} ermittelt pro Spalte die
 * naechsten Profile (glatter Kernel) und mittelt deren PARAMETER — die Noise-Basisfunktionen
 * selbst bleiben geteilt, das Blending kostet daher nur ein paar Lerps.
 *
 * <p>Achsen-Skala 0 = Achse ignorieren (z.B. Berge unabhaengig von Temperatur/Feuchte).
 * Ozean/Strand haben KEIN Profil — sie bleiben Kontinentalitaets-Schwellen (s. BiomeWeights).
 *
 * @param biome           zugehoeriges Biom (Material/Vegetation/Tint)
 * @param t               Zielpunkt Temperatur
 * @param h               Zielpunkt Feuchte
 * @param c               Zielpunkt Kontinentalitaet
 * @param e               Zielpunkt Erosion
 * @param v               Zielpunkt Variante
 * @param sT              Achsen-Skala Temperatur (0 = ignorieren)
 * @param sH              Achsen-Skala Feuchte
 * @param sC              Achsen-Skala Kontinentalitaet
 * @param sE              Achsen-Skala Erosion
 * @param sV              Achsen-Skala Variante
 * @param radius          Kernel-Radius im skalierten Klimaraum (Reichweite des Profils)
 * @param weightScale     Dominanz-Prior (>1 = setzt sich gegen breite Nachbarn durch, z.B. Berge)
 * @param minShare        Mindest-Gewichtsanteil (0..1), ab dem das Biom das LABEL beansprucht —
 *                        0 = wie bisher (argmax genuegt). Fuer „Drama-Biome" (Canyon): das Label
 *                        soll erst dort gelten, wo die Terrain-Optik auch wirklich entsteht;
 *                        darunter faellt die Wahl auf das zweitbeste Profil (s. BiomeWeights.pick)
 * @param baseOffset      Grundniveau-Aufschlag in Bloecken (zur Kueste hin ausgeblendet)
 * @param detailMul       Multiplikator der erosionsgesteuerten Detail-Amplitude (1 = V2)
 * @param mountainAmp     Ridged-Berg-Aufschlag in Bloecken (0 = keine Berge; V2: 170)
 * @param plateauMul      Staerke der Plateau-Kappung im Bergterm (1 = V2, >1 = Mesa-Deckel)
 * @param shapeAmpMax     maximale 3D-Verformung der Oberflaeche in Bloecken (V2: 12)
 * @param cliffHeight     Kuesten-Klippenwand in Bloecken (0 = normale Kueste; Fjord: ~70)
 * @param terraceStrength Terrassen-Staerke 0..1 (Canyon/Mesa-Stufen); der GEBLENDETE Wert wird
 *                        im Generator remappt (terraceMix), damit der Biom-Kern trotz
 *                        Blend-Verduennung volle Stufen bekommt
 */
public record BiomeTerrainProfile(
        Biome biome,
        float t, float h, float c, float e, float v,
        float sT, float sH, float sC, float sE, float sV,
        float radius, float weightScale, float minShare,
        float baseOffset, float detailMul, float mountainAmp, float plateauMul,
        float shapeAmpMax, float cliffHeight, float terraceStrength) {

    /** Quadrierte, achsen-skalierte Distanz des Klimas zum Zielpunkt. */
    public float dist2(ClimateV3 cl) {
        float dt = (cl.temperature() - this.t) * this.sT;
        float dh = (cl.humidity() - this.h) * this.sH;
        float dc = (cl.continentalness() - this.c) * this.sC;
        float de = (cl.erosion() - this.e) * this.sE;
        float dv = (cl.variant() - this.v) * this.sV;
        return dt * dt + dh * dh + dc * dc + de * de + dv * dv;
    }

    /**
     * Kernel-Gewicht 0..weightScale: quadratischer Abfall zum Radius, C1-stetig am Rand —
     * kein hartes k-nearest-Cutoff (das wuerde beim Rangwechsel entfernter Biome "poppen").
     */
    public float weight(ClimateV3 cl) {
        float d2 = this.dist2(cl);
        float r2 = this.radius * this.radius;
        if (d2 >= r2) return 0F;
        float f = 1F - d2 / r2;
        return this.weightScale * f * f;
    }
}
