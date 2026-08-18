package de.skyengine.graphics.post.passes;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class UnderwaterFogPassTest {

    @Test
    void matchesModernVanillaDistanceRamp() {
        assertEquals(0F, UnderwaterFogPass.fogFactor(UnderwaterFogPass.FOG_START));
        assertEquals(8F / 104F, UnderwaterFogPass.fogFactor(0F), 0.000001F);
        assertEquals(1F, UnderwaterFogPass.fogFactor(UnderwaterFogPass.FOG_END));
    }

    @Test
    void clampsOutsideFogRange() {
        assertEquals(0F, UnderwaterFogPass.fogFactor(-100F));
        assertEquals(1F, UnderwaterFogPass.fogFactor(500F));
    }

    @Test
    void scalesEndDistanceWithWaterVision() {
        assertEquals(24F, UnderwaterFogPass.fogEnd(0F));
        assertEquals(57.6F, UnderwaterFogPass.fogEnd(0.6F), 0.00001F);
        assertEquals(96F, UnderwaterFogPass.fogEnd(1F));
    }

    @Test
    void brightensVanillaWaterFogColorWithWaterVision() {
        assertEquals(0x050533, UnderwaterFogPass.fogColor(0F));
        assertEquals(0x1111AD, UnderwaterFogPass.fogColor(0.6F));
        assertEquals(0x1919FF, UnderwaterFogPass.fogColor(1F));
    }
}
