package de.skyengine.graphics.gui;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class GuiFullscreenLayerTest {
    @Test
    void backgroundCoverPreservesAspectRatioAcrossViewportShapes() {
        assertCover(1920F, 1080F, 0F, 0F, 1920F, 1080F);
        assertCover(2560F, 1080F, 0F, -180F, 2560F, 1440F);
        assertCover(1080F, 1920F, -1166.6666F, 0F, 3413.3333F, 1920F);
    }

    @Test
    void overlayAlphaIsClamped() {
        assertEquals(0F, GuiManager.clampOverlayAlpha(-0.5F));
        assertEquals(0.35F, GuiManager.clampOverlayAlpha(0.35F));
        assertEquals(1F, GuiManager.clampOverlayAlpha(1.5F));
    }

    @Test
    void fullscreenAssetsHaveExpectedDimensionsAndVignetteAlpha() throws IOException {
        BufferedImage background = read("/game/textures/ui/background.png");
        BufferedImage vignette = read("/game/textures/ui/vignette.png");

        assertEquals(1920, background.getWidth());
        assertEquals(1080, background.getHeight());
        assertEquals(1920, vignette.getWidth());
        assertEquals(1080, vignette.getHeight());
        assertTrue(vignette.getColorModel().hasAlpha());
    }

    private static BufferedImage read(String path) throws IOException {
        try (InputStream stream = GuiFullscreenLayerTest.class.getResourceAsStream(path)) {
            assertNotNull(stream, "Missing test resource " + path);
            BufferedImage image = ImageIO.read(stream);
            assertNotNull(image, "Unreadable image " + path);
            return image;
        }
    }

    private static void assertCover(float viewportWidth, float viewportHeight,
                                    float expectedX, float expectedY,
                                    float expectedWidth, float expectedHeight) {
        GuiManager.CoverBounds bounds = GuiManager.coverBounds(
                viewportWidth, viewportHeight, 1920F, 1080F);
        assertEquals(expectedX, bounds.x(), 0.001F);
        assertEquals(expectedY, bounds.y(), 0.001F);
        assertEquals(expectedWidth, bounds.width(), 0.001F);
        assertEquals(expectedHeight, bounds.height(), 0.001F);
    }
}
