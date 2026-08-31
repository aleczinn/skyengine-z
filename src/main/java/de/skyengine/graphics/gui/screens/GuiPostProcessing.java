package de.skyengine.graphics.gui.screens;

import de.skyengine.core.SkyEngine;
import de.skyengine.core.i18n.I18n;
import de.skyengine.core.settings.GameSettings;
import de.skyengine.graphics.gui.GuiManager;
import de.skyengine.graphics.gui.GuiScreen;
import de.skyengine.graphics.gui.layout.HStack;
import de.skyengine.graphics.gui.layout.VStack;
import de.skyengine.graphics.gui.widget.CycleButton;
import de.skyengine.graphics.gui.widget.Slider;
import de.skyengine.graphics.post.PostProcessingSettings;

import java.util.Locale;

import static de.skyengine.graphics.gui.screens.GuiOptionsMenu.CELL_H;
import static de.skyengine.graphics.gui.screens.GuiOptionsMenu.CELL_W;

/** Benutzeroberfläche für alle derzeit tatsächlich ausgewerteten Post-Processing-Parameter. */
public final class GuiPostProcessing extends GuiOptionsScreen {

    private static final int VISIBLE_ROWS = 8;
    private static final float ROW_GAP = 4;

    private final GameSettings gameSettings = GameSettings.get();
    private final PostProcessingSettings settings = SkyEngine.get().getPostProcessor().getSettings();

    public GuiPostProcessing(GuiScreen parent) {
        super(parent);
    }

    @Override
    protected String title() {
        return I18n.tr("options.post.title");
    }

    @Override
    protected float maxScrollViewportHeight() {
        return VISIBLE_ROWS * CELL_H + (VISIBLE_ROWS - 1) * ROW_GAP;
    }

    @Override
    protected void buildContent(GuiManager gui, VStack content) {
        CycleButton<PostProcessingSettings.AntiAliasingMode> aa = new CycleButton<>(
                I18n.tr("options.post.aa_mode"), CELL_W, CELL_H,
                PostProcessingSettings.AntiAliasingMode.values(), this.settings.getAaMode(),
                mode -> I18n.tr("options.post.aa_" + mode.name().toLowerCase(Locale.ROOT)),
                this.settings::setAaMode)
                .tooltipOf(mode -> I18n.tr("options.post.aa_hint_" + mode.name().toLowerCase(Locale.ROOT)));

        int configuredSamples = this.gameSettings.msaaSamples > 0 ? this.gameSettings.msaaSamples : 4;
        CycleButton<Integer> samples = new CycleButton<>(
                I18n.tr("options.post.samples"), CELL_W, CELL_H,
                new Integer[]{2, 4, 8, 16}, configuredSamples,
                value -> value + "x", value -> this.gameSettings.msaaSamples = value)
                .tooltipOf(value -> I18n.tr("options.post.samples_hint", value));

        CycleButton<PostProcessingSettings.TonemapOperator> tonemap = new CycleButton<>(
                I18n.tr("options.post.tonemap"), CELL_W, CELL_H,
                PostProcessingSettings.TonemapOperator.values(), this.settings.getTonemapOperator(),
                mode -> I18n.tr("options.post.tonemap_" + mode.name().toLowerCase(Locale.ROOT)),
                this.settings::setTonemapOperator);

        Slider exposure = slider(PostProcessingSettings.EXPOSURE_MIN, PostProcessingSettings.EXPOSURE_MAX,
                0.05, this.settings.getExposure(), "exposure", GuiPostProcessing::decimal,
                value -> this.settings.setExposure((float) value));
        Slider gamma = slider(PostProcessingSettings.GAMMA_MIN, PostProcessingSettings.GAMMA_MAX,
                0.05, this.settings.getGamma(), "gamma", GuiPostProcessing::decimal,
                value -> this.settings.setGamma((float) value));
        Slider contrast = multiplier(this.settings.getContrast(), "contrast", this.settings::setContrast);
        Slider brightness = offset(this.settings.getBrightness(), "brightness", this.settings::setBrightness);
        Slider saturation = multiplier(this.settings.getSaturation(), "saturation", this.settings::setSaturation);
        Slider vibrance = colorShift(this.settings.getVibrance(), "vibrance", this.settings::setVibrance);
        Slider temperature = colorShift(this.settings.getTemperature(), "temperature", this.settings::setTemperature);
        Slider tint = colorShift(this.settings.getTint(), "tint", this.settings::setTint);
        Slider lift = offset(this.settings.getLift(), "lift", this.settings::setLift);
        Slider gain = multiplier(this.settings.getGain(), "gain", this.settings::setGain);
        Slider shadows = multiplier(this.settings.getShadows(), "shadows", this.settings::setShadows);
        Slider midtones = multiplier(this.settings.getMidtones(), "midtones", this.settings::setMidtones);
        Slider highlights = multiplier(this.settings.getHighlights(), "highlights", this.settings::setHighlights);

        Slider history = slider(PostProcessingSettings.TAA_HISTORY_MIN, PostProcessingSettings.TAA_HISTORY_MAX,
                0.01, this.settings.getTaaHistoryWeight(), "taa_history", GuiPostProcessing::percent,
                value -> this.settings.setTaaHistoryWeight((float) value));
        history.tooltip(I18n.tr("options.post.taa_history_hint"));
        Slider mipBias = slider(PostProcessingSettings.TAA_MIP_BIAS_MIN, PostProcessingSettings.TAA_MIP_BIAS_MAX,
                0.05, this.settings.getTaaMipBias(), "taa_mip_bias", GuiPostProcessing::decimal,
                value -> this.settings.setTaaMipBias((float) value));
        mipBias.tooltip(I18n.tr("options.post.taa_mip_bias_hint"));
        Slider sharpen = slider(PostProcessingSettings.SHARPEN_MIN, PostProcessingSettings.SHARPEN_MAX,
                0.01, this.settings.getSharpen(), "sharpen", GuiPostProcessing::percent,
                value -> this.settings.setSharpen((float) value));
        sharpen.tooltip(I18n.tr("options.post.sharpen_hint"));

        content.add(new HStack(4, aa, samples));
        content.add(new HStack(4, tonemap, exposure));
        content.add(new HStack(4, gamma, contrast));
        content.add(new HStack(4, brightness, saturation));
        content.add(new HStack(4, vibrance, temperature));
        content.add(new HStack(4, tint, lift));
        content.add(new HStack(4, gain, sharpen));
        content.add(new HStack(4, shadows, midtones));
        content.add(new HStack(4, highlights, history));
        content.add(new HStack(4, mipBias, null));
    }

    private Slider multiplier(float current, String key, java.util.function.Consumer<Float> setter) {
        return slider(PostProcessingSettings.MULTIPLIER_MIN, PostProcessingSettings.MULTIPLIER_MAX,
                0.05, current, key, GuiPostProcessing::percent,
                value -> setter.accept((float) value));
    }

    private Slider offset(float current, String key, java.util.function.Consumer<Float> setter) {
        return slider(PostProcessingSettings.OFFSET_MIN, PostProcessingSettings.OFFSET_MAX,
                0.01, current, key, GuiPostProcessing::signedPercent,
                value -> setter.accept((float) value));
    }

    private Slider colorShift(float current, String key, java.util.function.Consumer<Float> setter) {
        return slider(PostProcessingSettings.COLOR_SHIFT_MIN, PostProcessingSettings.COLOR_SHIFT_MAX,
                0.05, current, key, GuiPostProcessing::signedPercent,
                value -> setter.accept((float) value));
    }

    private static Slider slider(double min, double max, double step, double current, String key,
                                 java.util.function.DoubleFunction<String> valueFormat,
                                 java.util.function.DoubleConsumer setter) {
        return new Slider(CELL_W, CELL_H, min, max, step, current,
                value -> I18n.tr("options.post." + key, valueFormat.apply(value)), setter, null);
    }

    private static String decimal(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }

    private static String percent(double value) {
        return Math.round(value * 100.0) + " %";
    }

    private static String signedPercent(double value) {
        long percent = Math.round(value * 100.0);
        return (percent > 0 ? "+" : "") + percent + " %";
    }

    @Override
    public void onClose() {
        this.gameSettings.save();
        this.settings.save();
    }
}
