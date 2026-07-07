package de.skyengine.game.world.generator.biome;

import de.skyengine.game.world.generator.climate.ClimateV3;

/**
 * Gewichtetes Biome-Blending des V3-Generators: pro Position werden die Kernel-Gewichte aller
 * {@link BiomeTerrainProfile}s im Klimaraum berechnet, die Terrain-PARAMETER gewichtet gemittelt
 * ({@link #blend}) und das dominante Biom per argmax bestimmt ({@link #pick}) — Terrain und
 * Biomkarte koennen sich dadurch nie widersprechen. Ozean und Strandband bleiben wie in V2
 * reine Kontinentalitaets-Schwellen ({@link Biomes#C_OCEAN}/{@link Biomes#C_BEACH}).
 *
 * <p>Reine Arithmetik ohne Noise-Auswertung (~11 Distanzen) — vernachlaessigbar gegen die
 * Klima-/Detail-Noises pro Spalte. Alles pure Funktionen des Klimas, threadsicher.
 *
 * <p><b>Achtung Init-Reihenfolge:</b> Die Profile fangen {@link Biomes}-Konstanten (und damit
 * {@code Blocks.*}-IDs) beim Klassen-Init ein — wie {@link Biomes} erst NACH
 * {@code Blocks.bootstrap(...)} beruehren, nie aus einem Generator-Konstruktor.
 */
public final class BiomeWeights {

    /**
     * Geblendete Terrain-Parameter einer Spalte + dominantes Biom (argmax).
     * Semantik der Felder: s. {@link BiomeTerrainProfile}.
     */
    public record TerrainParams(Biome biome, float baseOffset, float detailMul, float mountainAmp,
                                float plateauMul, float shapeAmpMax, float cliffHeight,
                                float terraceStrength) {
    }

    /*
     * Zielpunkte im Klimaraum. Orientierung an der V2-Inland-Tabelle (T/H-Buckets ±0.25/±0.2),
     * Berge an mountainWeight (C hoch, E niedrig). DESERT und CANYON teilen sich die heisse
     * Trockenzone und werden ueber die VARIANT-Achse getrennt. Alle Inland-Klimabiome tragen
     * eine MILDE Kuesten-Aversion (c=0.35, sC=0.8): ohne sie verduennen ihre breiten Kernel
     * die Kuesten-Spezialisten (Fjord-Klippenhoehe!) auf die Haelfte; tief im Binnenland ist
     * der Term vernachlaessigbar.
     *          Biom             | Zielpunkt t,h,c,e,v                | Skalen sT,sH,sC,sE,sV     | R, ws
     */
    private static final BiomeTerrainProfile[] PROFILES = {
            new BiomeTerrainProfile(Biomes.PLAINS,
                    0.00F, -0.05F, 0.35F, 0.25F, 0F, 1F, 1F, 0.8F, 0.5F, 0F, 0.90F, 1F, 0F,
                    0F, 1F, 0F, 1F, 12F, 0F, 0F),
            new BiomeTerrainProfile(Biomes.DESERT,
                    0.55F, -0.55F, 0.35F, 0F, -0.35F, 1F, 1F, 0.8F, 0F, 0.8F, 0.95F, 1F, 0F,
                    0F, 1F, 0F, 1F, 12F, 0F, 0F),
            new BiomeTerrainProfile(Biomes.JUNGLE,
                    0.55F, 0.55F, 0.35F, 0F, 0F, 1F, 1F, 0.8F, 0F, 0F, 0.90F, 1F, 0F,
                    0F, 1F, 0F, 1F, 12F, 0F, 0F),
            new BiomeTerrainProfile(Biomes.SPRUCE_FOREST,
                    -0.55F, -0.20F, 0.35F, 0F, 0F, 1F, 0.7F, 0.8F, 0F, 0F, 0.95F, 1F, 0F,
                    0F, 1F, 0F, 1F, 12F, 0F, 0F),
            new BiomeTerrainProfile(Biomes.REDWOOD_FOREST,
                    -0.20F, 0.55F, 0.35F, 0F, 0F, 0.8F, 1F, 0.8F, 0F, 0F, 0.90F, 1F, 0F,
                    0F, 1F, 0F, 1F, 12F, 0F, 0F),
            /* Berge: nur C/E zaehlen (temperaturunabhaengig wie V2s mountainWeight);
             * hoher Prior, damit die breiten Klima-Biome das Massiv nicht verduennen */
            new BiomeTerrainProfile(Biomes.EXTREME_HILLS,
                    0F, 0F, 0.55F, -0.60F, 0F, 0F, 0F, 1.6F, 1.4F, 0F, 1.00F, 2.2F, 0F,
                    0F, 1F, 200F, 1F, 12F, 0F, 0F),
            /* Fjord: kalte, zerklueftete Kueste — enges C-Band um die Kuestenlinie,
             * Klippenwand + Berge, staerkere 3D-Verformung fuer Felswaende */
            new BiomeTerrainProfile(Biomes.FJORD_HIGHLANDS,
                    -0.45F, 0F, -0.02F, -0.35F, 0F, 0.9F, 0F, 2.0F, 0.8F, 0F, 0.90F, 3.0F, 0F,
                    0F, 1.1F, 120F, 1F, 16F, 120F, 0F),
            /* Canyon: heiss-trocken wie die Wueste, per VARIANT getrennt; Terrassen +
             * Mesa-Deckel (plateauMul) + eigenes Grundniveau. minShare 0.5: das Label gilt
             * nur in der Kernzone, wo Terrassen/Mesas/Strata auch wirklich entstehen —
             * sonst traegt der argmax-Rand Canyon-Material auf Nachbar-Terrainform */
            new BiomeTerrainProfile(Biomes.CANYON,
                    0.50F, -0.40F, 0.35F, -0.30F, 0.50F, 1F, 0.9F, 0.8F, 0.7F, 1.2F, 0.95F, 1.3F, 0.5F,
                    14F, 1.15F, 70F, 2.2F, 14F, 0F, 0.85F),
    };

    /**
     * Geblendete Terrain-Parameter am (glatten) Klima. Faellt ausserhalb aller Kernel-Radien
     * stetig auf das naechstgelegene Profil zurueck (generisch verschwindet dort zuletzt genau
     * ein Kernel — die Normierung laeuft also von selbst gegen dieses Profil).
     */
    public static TerrainParams blend(ClimateV3 cl) {
        float total = 0F;
        float base = 0F, detail = 0F, mountain = 0F, plateau = 0F, shape = 0F, cliff = 0F, terrace = 0F;
        float bestWeight = -1F;
        BiomeTerrainProfile best = null;
        for (BiomeTerrainProfile p : PROFILES) {
            float w = p.weight(cl);
            if (w > bestWeight) {
                bestWeight = w;
                best = p;
            }
            if (w <= 0F) continue;
            total += w;
            base += w * p.baseOffset();
            detail += w * p.detailMul();
            mountain += w * p.mountainAmp();
            plateau += w * p.plateauMul();
            shape += w * p.shapeAmpMax();
            cliff += w * p.cliffHeight();
            terrace += w * p.terraceStrength();
        }
        if (total <= 0F) {
            /* Ausserhalb aller Radien: naechstes Profil pur (Distanz statt Gewicht vergleichen) */
            best = nearest(cl);
            return new TerrainParams(best.biome(), best.baseOffset(), best.detailMul(),
                    best.mountainAmp(), best.plateauMul(), best.shapeAmpMax(),
                    best.cliffHeight(), best.terraceStrength());
        }
        float inv = 1F / total;
        return new TerrainParams(best.biome(), base * inv, detail * inv, mountain * inv,
                plateau * inv, shape * inv, cliff * inv, terrace * inv);
    }

    /**
     * Dominantes Biom am Klima — fuer Material/Vegetation (mit gewarptem Klima aufrufen)
     * und Tints. Ozean/Strand per Kontinentalitaets-Schwelle wie in V2. Profile mit
     * {@code minShare > 0} beanspruchen das Label erst ab diesem Gewichtsanteil — der blosse
     * argmax-Sieg reicht bei „Drama-Biomen" nicht (das Label truege sonst weit in Randzonen,
     * deren Terrainform noch von den Nachbarn dominiert wird); darunter gilt das zweitbeste
     * Profil (breite Klimabiome haben minShare 0, es gibt also immer einen guelten Zweiten).
     */
    public static Biome pick(ClimateV3 cl) {
        if (cl.continentalness() < Biomes.C_OCEAN) return Biomes.OCEAN;
        if (cl.continentalness() < Biomes.C_BEACH) {
            /* Karibikstrand nur in heiss-feuchten Kuestenregionen (wie V2) */
            return (cl.temperature() > 0.35F && cl.humidity() > 0.1F)
                    ? Biomes.CARIBBEAN_BEACH : Biomes.BEACH;
        }
        float total = 0F;
        float bestWeight = 0F, secondWeight = 0F;
        BiomeTerrainProfile best = null, second = null;
        for (BiomeTerrainProfile p : PROFILES) {
            float w = p.weight(cl);
            total += w;
            if (w > bestWeight) {
                second = best;
                secondWeight = bestWeight;
                best = p;
                bestWeight = w;
            } else if (w > secondWeight) {
                second = p;
                secondWeight = w;
            }
        }
        if (best == null) return nearest(cl).biome();
        if (best.minShare() > 0F && second != null && bestWeight / total < best.minShare()) {
            return second.biome();
        }
        return best.biome();
    }

    /**
     * Anteil des dominanten Profils an der Gewichtssumme: 1 = Biom-Kern, kleinere Werte =
     * Blend-Zone. Nur fuer Debug-Karten (GeneratorMapExporter), nicht im Hot-Path.
     */
    public static float dominance(ClimateV3 cl) {
        float total = 0F, best = 0F;
        for (BiomeTerrainProfile p : PROFILES) {
            float w = p.weight(cl);
            total += w;
            if (w > best) best = w;
        }
        return (total <= 0F) ? 1F : best / total;
    }

    /** Profil mit der kleinsten radius-normierten Distanz (Fallback ausserhalb aller Kernel). */
    private static BiomeTerrainProfile nearest(ClimateV3 cl) {
        BiomeTerrainProfile best = PROFILES[0];
        float bestNorm = Float.MAX_VALUE;
        for (BiomeTerrainProfile p : PROFILES) {
            float norm = p.dist2(cl) / (p.radius() * p.radius());
            if (norm < bestNorm) {
                bestNorm = norm;
                best = p;
            }
        }
        return best;
    }

    private BiomeWeights() {
    }
}
