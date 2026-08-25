package de.skyengine.game.world.dimension;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class PortalCoordinatesTest {

    @Test
    void scalesOverworldAndNetherCoordinatesInBothDirections() {
        assertEquals(10, PortalCoordinates.scale(80.0,
                DimensionEnvironment.OVERWORLD, DimensionEnvironment.NETHER));
        assertEquals(80, PortalCoordinates.scale(10.0,
                DimensionEnvironment.NETHER, DimensionEnvironment.OVERWORLD));
        assertEquals(-2, PortalCoordinates.scale(-9.0,
                DimensionEnvironment.OVERWORLD, DimensionEnvironment.NETHER));
    }

    @Test
    void derivesMinecraftSearchRadiiFromTargetScale() {
        assertEquals(16, PortalCoordinates.searchRadius(DimensionEnvironment.NETHER));
        assertEquals(128, PortalCoordinates.searchRadius(DimensionEnvironment.OVERWORLD));
    }
}
