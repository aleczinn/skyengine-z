package de.skyengine.game.world.generator.generators;

/**
 * Ein Worley-See: fester Spiegel pro See (flach!), das Becken wird unter den Spiegel gecarvt.
 * Frueher als geschachtelter Record in {@link AlphaWorldGeneratorV2} — top-level, damit
 * {@link RiverNetwork} ueber {@link RiverTerrain} generator-unabhaengig damit arbeiten kann.
 */
record Lake(int centerX, int centerZ, int radius, int level) {
}
