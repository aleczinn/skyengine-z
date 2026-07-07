package de.skyengine.game.world.generator.climate;

/**
 * Die fuenf Klimawerte einer Position fuer den V3-Generator (je ca. -1..1). Gegenueber
 * {@link Climate} kommt {@code variant} hinzu: ein unabhaengiges, sehr niederfrequentes Feld,
 * das Biome mit gleichem Klima trennt (Wueste vs. Canyon, spaeter Wald vs. Birkenwald) —
 * ohne dass dafuer Temperatur/Feuchte kuenstlich verbogen werden muessen.
 *
 * @param temperature     kalt (-1) bis heiss (+1)
 * @param humidity        trocken (-1) bis feucht (+1)
 * @param continentalness Ozean (-1) bis Landesinneres (+1)
 * @param erosion         zerklueftet (-1) bis glatt (+1)
 * @param variant         Biom-Variante innerhalb derselben Klimazone
 */
public record ClimateV3(float temperature, float humidity, float continentalness, float erosion,
                        float variant) {
}
