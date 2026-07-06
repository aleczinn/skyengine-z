package de.skyengine.game.world.generator.climate;

/**
 * Die vier Klimawerte einer Position (je ca. -1..1). Aus ihrer Kombination werden Biome und
 * Hoehenmodell abgeleitet — nie Biome direkt platzieren.
 *
 * @param temperature     kalt (-1) bis heiss (+1)
 * @param humidity        trocken (-1) bis feucht (+1)
 * @param continentalness Ozean (-1) bis Landesinneres (+1)
 * @param erosion         zerklueftet (-1) bis glatt (+1)
 */
public record Climate(float temperature, float humidity, float continentalness, float erosion) {
}
