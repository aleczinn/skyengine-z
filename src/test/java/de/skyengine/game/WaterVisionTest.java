package de.skyengine.game;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class WaterVisionTest {

    @Test
    void followsMinecraftRamp() {
        assertEquals(0F, WaterVision.factor(0));
        assertEquals(0.6F, WaterVision.factor(100), 0.000001F);
        assertEquals(0.8F, WaterVision.factor(350), 0.000001F);
        assertEquals(1F, WaterVision.factor(600));
    }

    @Test
    void decaysTenTimesFasterOutsideWater() {
        WaterVision vision = new WaterVision();
        for (int i = 0; i < 120; i++) vision.tick(true);
        vision.tick(false);
        assertEquals(110, vision.ticks());
        for (int i = 0; i < 11; i++) vision.tick(false);
        assertEquals(0, vision.ticks());
    }
}
