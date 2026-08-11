package de.skyengine.graphics.post;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class PostProcessingSettingsTest {

    @Test
    void resetRestoresPhotonDefaults() {
        PostProcessingSettings settings = new PostProcessingSettings();
        settings.setExposure(0.25F);
        settings.setSaturation(0.0F);
        settings.setBloomIntensity(0.0F);
        settings.setSharpen(0.0F);
        settings.setAaMode(PostProcessingSettings.AntiAliasingMode.NONE);

        settings.resetToDefaults();

        assertEquals(1.0F, settings.getExposure());
        assertEquals(1.40F, settings.getSaturation());
        assertEquals(1.0F, settings.getBloomIntensity());
        assertEquals(1.0F, settings.getVignette());
        assertEquals(0.5F, settings.getSharpen());
        assertEquals(0.0F, settings.getTaaMipBias());
        assertEquals(PostProcessingSettings.AntiAliasingMode.TAA,
                settings.getAaMode());
    }
}
