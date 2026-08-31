package de.skyengine.graphics.post;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class PostProcessingSettingsTest {

    @Test
    void guiFacingSettersClampToPersistedRanges() {
        PostProcessingSettings settings = new PostProcessingSettings();

        settings.setExposure(Float.POSITIVE_INFINITY);
        settings.setGamma(Float.NEGATIVE_INFINITY);
        settings.setContrast(3.0F);
        settings.setBrightness(-2.0F);
        settings.setSaturation(-1.0F);
        settings.setVibrance(2.0F);
        settings.setTemperature(-2.0F);
        settings.setTint(2.0F);
        settings.setLift(2.0F);
        settings.setGain(-1.0F);
        settings.setShadows(3.0F);
        settings.setMidtones(-1.0F);
        settings.setHighlights(3.0F);
        settings.setTaaHistoryWeight(2.0F);
        settings.setTaaMipBias(-3.0F);
        settings.setSharpen(2.0F);

        assertEquals(PostProcessingSettings.EXPOSURE_MAX, settings.getExposure());
        assertEquals(PostProcessingSettings.GAMMA_MIN, settings.getGamma());
        assertEquals(PostProcessingSettings.MULTIPLIER_MAX, settings.getContrast());
        assertEquals(PostProcessingSettings.OFFSET_MIN, settings.getBrightness());
        assertEquals(PostProcessingSettings.MULTIPLIER_MIN, settings.getSaturation());
        assertEquals(PostProcessingSettings.COLOR_SHIFT_MAX, settings.getVibrance());
        assertEquals(PostProcessingSettings.COLOR_SHIFT_MIN, settings.getTemperature());
        assertEquals(PostProcessingSettings.COLOR_SHIFT_MAX, settings.getTint());
        assertEquals(PostProcessingSettings.OFFSET_MAX, settings.getLift());
        assertEquals(PostProcessingSettings.MULTIPLIER_MIN, settings.getGain());
        assertEquals(PostProcessingSettings.MULTIPLIER_MAX, settings.getShadows());
        assertEquals(PostProcessingSettings.MULTIPLIER_MIN, settings.getMidtones());
        assertEquals(PostProcessingSettings.MULTIPLIER_MAX, settings.getHighlights());
        assertEquals(PostProcessingSettings.TAA_HISTORY_MAX, settings.getTaaHistoryWeight());
        assertEquals(PostProcessingSettings.TAA_MIP_BIAS_MIN, settings.getTaaMipBias());
        assertEquals(PostProcessingSettings.SHARPEN_MAX, settings.getSharpen());
    }

    @Test
    void msaaSamplesAreOnlyEffectiveInMsaaMode() {
        PostProcessingSettings settings = new PostProcessingSettings();

        settings.setAaMode(PostProcessingSettings.AntiAliasingMode.TAA);
        assertEquals(0, settings.effectiveMsaaSamples(8));

        settings.setAaMode(PostProcessingSettings.AntiAliasingMode.MSAA);
        assertEquals(4, settings.effectiveMsaaSamples(0));
        assertEquals(8, settings.effectiveMsaaSamples(8));
    }

    @Test
    void saveAndLoadRoundTripUserValues() {
        PostProcessingSettings settings = new PostProcessingSettings();
        settings.setAaMode(PostProcessingSettings.AntiAliasingMode.TAA_FXAA);
        settings.setTonemapOperator(PostProcessingSettings.TonemapOperator.ACES);
        settings.setExposure(1.75F);
        settings.setSaturation(1.25F);
        settings.setVibrance(0.35F);
        settings.setSharpen(0.4F);
        settings.save();

        PostProcessingSettings loaded = PostProcessingSettings.load();

        assertEquals(PostProcessingSettings.AntiAliasingMode.TAA_FXAA, loaded.getAaMode());
        assertEquals(PostProcessingSettings.TonemapOperator.ACES, loaded.getTonemapOperator());
        assertEquals(1.75F, loaded.getExposure());
        assertEquals(1.25F, loaded.getSaturation());
        assertEquals(0.35F, loaded.getVibrance());
        assertEquals(0.4F, loaded.getSharpen());
        assertTrue(loaded.consumeDirty());
        assertFalse(loaded.consumeDirty());
    }
}
