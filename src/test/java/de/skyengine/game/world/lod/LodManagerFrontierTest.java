package de.skyengine.game.world.lod;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class LodManagerFrontierTest {

    @Test
    void distanceBandsAreDirectionIndependentAndContiguous() {
        assertEquals(0, LodManager.distanceBand(0, 0));
        assertEquals(0, LodManager.distanceBand(127.99, 0));
        assertEquals(1, LodManager.distanceBand(128, 0));
        assertEquals(1, LodManager.distanceBand(-128, 0));
        assertEquals(2, LodManager.distanceBand(256, 0));
    }
}
