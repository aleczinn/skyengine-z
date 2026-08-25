package de.skyengine.game.world.dimension;

/** Dimensionsneutrale Koordinaten- und Suchradiusregeln fuer skalierte Portalreisen. */
public final class PortalCoordinates {

    private static final double OVERWORLD_SEARCH_RADIUS = 128.0;

    public static int scale(double coordinate, DimensionEnvironment source,
                            DimensionEnvironment target) {
        return (int) Math.floor(coordinate * source.coordinateScale() / target.coordinateScale());
    }

    public static int searchRadius(DimensionEnvironment target) {
        return Math.max(1, (int) Math.floor(OVERWORLD_SEARCH_RADIUS / target.coordinateScale()));
    }

    private PortalCoordinates() {}
}
