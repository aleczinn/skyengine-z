package de.skyengine.core.settings;

import org.junit.jupiter.api.Test;
import org.lwjgl.glfw.GLFW;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ZoomSettingsTest {

    @Test
    void defaultsUseConfiguredZoomFactorAndCKey() {
        assertEquals(GameSettings.ZOOM_FACTOR_DEFAULT, new GameSettings().zoomFactor);
        assertEquals(GLFW.GLFW_KEY_C, KeyBindings.defaults().get(KeyBindings.ZOOM));
        assertTrue(KeyBindings.orderedActions().contains(KeyBindings.ZOOM));
    }

    @Test
    void persistedZoomFactorIsClampedAndSnappedToHalfSteps() {
        assertEquals(GameSettings.ZOOM_FACTOR_MIN, GameSettings.normalizeZoomFactor(-5F));
        assertEquals(GameSettings.ZOOM_FACTOR_MAX, GameSettings.normalizeZoomFactor(20F));
        assertEquals(GameSettings.ZOOM_FACTOR_DEFAULT, GameSettings.normalizeZoomFactor(Float.NaN));
        assertEquals(4.5F, GameSettings.normalizeZoomFactor(4.3F));
        assertEquals(4F, GameSettings.normalizeZoomFactor(4.2F));
    }
}
