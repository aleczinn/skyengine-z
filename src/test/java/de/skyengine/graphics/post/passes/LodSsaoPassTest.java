package de.skyengine.graphics.post.passes;

import de.skyengine.core.settings.GameSettings;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class LodSsaoPassTest {

    @Test
    void qualityProfilesSelectTheirFixedResolution() {
        assertEquals(4, LodSsaoPass.targetDivisor(GameSettings.ScreenSpaceAoQuality.BASIC,
                1280, 720));
        assertEquals(2, LodSsaoPass.targetDivisor(GameSettings.ScreenSpaceAoQuality.HIGH,
                3840, 2160));
        assertEquals(2, LodSsaoPass.targetDivisor(GameSettings.ScreenSpaceAoQuality.AUTO,
                1600, 900));
        assertEquals(4, LodSsaoPass.targetDivisor(GameSettings.ScreenSpaceAoQuality.AUTO,
                2048, 1115));
    }
}
