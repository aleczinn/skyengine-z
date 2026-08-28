package de.skyengine.graphics.gui.screens;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class GuiCoalGeneratorTest {
    @Test void inactiveFlameHasNoActiveOverlay() {
        GuiCoalGenerator.FlameSlice flame = GuiCoalGenerator.flameSlice(0, 300);
        assertEquals(0F, flame.height(), 0.0001F);
        assertEquals(1F, flame.v0(), 0.0001F);
    }

    @Test void halfBurnedFuelDrawsTheBottomHalfOfTheActiveSprite() {
        GuiCoalGenerator.FlameSlice flame = GuiCoalGenerator.flameSlice(150, 300);
        assertEquals(6.5F, flame.height(), 0.0001F);
        assertEquals(.5F, flame.v0(), 0.0001F);
    }

    @Test void fullAndInvalidDurationsAreClamped() {
        GuiCoalGenerator.FlameSlice full = GuiCoalGenerator.flameSlice(400, 300);
        assertEquals(13F, full.height(), 0.0001F);
        assertEquals(0F, full.v0(), 0.0001F);
        assertEquals(0F, GuiCoalGenerator.flameSlice(20, 0).height(), 0.0001F);
    }
}
