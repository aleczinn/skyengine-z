package de.skyengine.graphics.framebuffer;

import de.skyengine.core.settings.GameSettings;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class FrameBufferLodMaskTest {

    @Test
    void maskExistsOnlyForActiveLodSsao() {
        GameSettings settings = new GameSettings();
        settings.lodEnabled = true;
        settings.ambientOcclusion = true;
        settings.renderDistance = 16;
        settings.lodMaxDistance = 128;
        settings.screenSpaceAoQuality = GameSettings.ScreenSpaceAoQuality.HIGH;
        assertTrue(FrameBuffer.wantsLodMask(settings));

        settings.screenSpaceAoQuality = GameSettings.ScreenSpaceAoQuality.OFF;
        assertFalse(FrameBuffer.wantsLodMask(settings));
        settings.screenSpaceAoQuality = GameSettings.ScreenSpaceAoQuality.HIGH;
        settings.ambientOcclusion = false;
        assertFalse(FrameBuffer.wantsLodMask(settings));
        settings.ambientOcclusion = true;
        settings.lodMaxDistance = settings.renderDistance;
        assertFalse(FrameBuffer.wantsLodMask(settings));
    }
}
