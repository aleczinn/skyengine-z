package de.skyengine.graphics.gui.screens;

import de.skyengine.core.SkyEngine;
import de.skyengine.core.i18n.I18n;
import de.skyengine.core.settings.GameSettings;
import de.skyengine.graphics.gui.GuiManager;
import de.skyengine.graphics.gui.GuiScreen;
import de.skyengine.graphics.gui.layout.HStack;
import de.skyengine.graphics.gui.layout.VStack;
import de.skyengine.graphics.gui.widget.CycleButton;
import de.skyengine.graphics.gui.widget.Button;
import de.skyengine.graphics.gui.widget.Slider;
import de.skyengine.graphics.post.PostProcessingSettings;
import de.skyengine.graphics.post.PostProcessingSettings.AntiAliasingMode;
import de.skyengine.graphics.shaderpack.ShaderPackManager;
import de.skyengine.graphics.shaderpack.ShaderPackManager.PackOption;
import de.skyengine.graphics.shaderpack.ShaderPackManifest;
import de.skyengine.graphics.gui.widget.GuiComponent;

import java.util.List;
import java.util.function.DoubleConsumer;
import java.util.ArrayList;
import java.util.Locale;

import static de.skyengine.graphics.gui.screens.GuiOptionsMenu.CELL_H;
import static de.skyengine.graphics.gui.screens.GuiOptionsMenu.CELL_W;

/** Shaderpack-Auswahl und pack-unabhängige Bild-/Postprocessing-Einstellungen. */
public final class GuiShaderPacks extends GuiOptionsScreen {

    public GuiShaderPacks(GuiScreen parent) {
        super(parent);
    }

    @Override
    protected String title() {
        return I18n.tr("options.shaderpacks.title");
    }

    @Override
    protected GuiComponent buildFooter(GuiManager gui) {
        Button reset = new Button(I18n.tr("options.shaderpacks.reset"), 150, 20, () -> {
            SkyEngine.get().getPostProcessor().getSettings().resetToDefaults();
            SkyEngine.get().getShaderPackManager().resetActiveSettings();
            GameSettings.get().msaaSamples = 4;
            this.init(gui, gui.vWidth(), gui.vHeight());
        });
        Button done = new Button(I18n.tr("gui.done"), 150, 20, () -> this.goBack(gui));
        return new HStack(6, reset, done);
    }

    @Override
    protected void buildContent(GuiManager gui, VStack content) {
        ShaderPackManager manager = SkyEngine.get().getShaderPackManager();
        PostProcessingSettings post = SkyEngine.get().getPostProcessor().getSettings();
        GameSettings game = GameSettings.get();

        List<PackOption> available = manager.availablePacks();
        PackOption[] packs = available.toArray(PackOption[]::new);
        PackOption current = available.stream()
                .filter(option -> option.id().equals(manager.active().manifest().id))
                .findFirst().orElse(packs[0]);

        CycleButton<PackOption> pack = new CycleButton<>(I18n.tr("options.shaderpacks.pack"), CELL_W, CELL_H,
                packs, current, PackOption::name, option -> manager.requestPack(option.id()));

        CycleButton<AntiAliasingMode> aa = new CycleButton<>(I18n.tr("options.video.aa"), CELL_W, CELL_H,
                AntiAliasingMode.values(), post.getAaMode(),
                mode -> I18n.tr("options.video.aa_" + mode.name().toLowerCase()), post::setAaMode);

        CycleButton<Integer> msaa = new CycleButton<>(I18n.tr("options.video.msaa_samples"), CELL_W, CELL_H,
                new Integer[]{2, 4, 8, 16}, game.msaaSamples == 0 ? 4 : game.msaaSamples,
                value -> value + "x", value -> game.msaaSamples = value)
                .tooltipOf(value -> I18n.tr("options.video.msaa_samples_hint"));

        Slider exposure = slider(25, 200, post.getExposure() * 100,
                "options.shaderpacks.exposure", value -> post.setExposure((float) value / 100F));
        Slider saturation = slider(0, 200, post.getSaturation() * 100,
                "options.shaderpacks.saturation", value -> post.setSaturation((float) value / 100F));
        /* Intern bleibt Vibrance ein Offset von -1..+1. Im UI ist sie wie Saturation als
           Faktor dargestellt: 100 % ist neutral, 0 % entsaettigt, 200 % verstaerkt. Das
           behaelt bestehende Konfigurationsdateien bei und beseitigt die missverstaendliche
           Anzeige "0 %" fuer den neutralen Standardwert. */
        Slider vibrance = slider(0, 200, (post.getVibrance() + 1F) * 100F,
                "options.shaderpacks.vibrance", value -> post.setVibrance((float) value / 100F - 1F));
        Slider contrast = slider(50, 150, post.getContrast() * 100,
                "options.shaderpacks.contrast", value -> post.setContrast((float) value / 100F));
        Slider gamma = slider(50, 150, post.getGamma() * 100,
                "options.shaderpacks.gamma", value -> post.setGamma((float) value / 100F));
        Slider bloom = slider(0, 200, post.getBloomIntensity() * 100,
                "options.shaderpacks.bloom", value -> post.setBloomIntensity((float) value / 100F));
        Slider threshold = slider(0, 400, post.getBloomThreshold() * 100,
                "options.shaderpacks.bloom_threshold", value -> post.setBloomThreshold((float) value / 100F));
        Slider sharpen = slider(0, 100, post.getSharpen() * 100,
                "options.shaderpacks.sharpen", value -> post.setSharpen((float) value / 100F));

        content.add(new HStack(4, pack, aa));
        content.add(new HStack(4, msaa, exposure));
        content.add(new HStack(4, saturation, vibrance));
        content.add(new HStack(4, contrast, gamma));
        content.add(new HStack(4, bloom, threshold));
        content.add(new HStack(4, sharpen));

        /* Diese Zeilen kommen vollständig aus pack.json. Externe Packs erhalten damit
           eigene Optionen, ohne dass ihre Uniforms in der Engine fest verdrahtet werden. */
        List<GuiComponent> packSettings = new ArrayList<>();
        for (ShaderPackManifest.Setting setting : manager.activeSettings()) {
            String label = I18n.has(setting.label) ? I18n.tr(setting.label) : setting.label;
            double currentValue = manager.settingValue(setting);
            GuiComponent component;
            if ("boolean".equals(setting.type)) {
                component = CycleButton.onOff(label, CELL_W, CELL_H, currentValue >= 0.5,
                        value -> manager.setSettingValue(setting, value ? 1.0 : 0.0));
            } else if ("choice".equals(setting.type)) {
                Double[] values = setting.values.toArray(Double[]::new);
                Double currentChoice = setting.values.stream()
                        .min((a, b) -> Double.compare(Math.abs(a - currentValue),
                                Math.abs(b - currentValue))).orElse(values[0]);
                component = new CycleButton<>(label, CELL_W, CELL_H, values, currentChoice,
                        value -> {
                            int index = setting.values.indexOf(value);
                            String option = setting.options.get(index);
                            return I18n.has(option) ? I18n.tr(option) : option;
                        }, value -> manager.setSettingValue(setting, value));
            } else {
                component = new Slider(CELL_W, CELL_H, setting.min, setting.max, setting.step,
                        currentValue, value -> setting.step < 0.1
                                ? String.format(Locale.ROOT, "%s: %.3f", label, value)
                                : setting.step < 1.0
                                ? String.format(Locale.ROOT, "%s: %.2f", label, value)
                                : String.format(Locale.ROOT, "%s: %.0f", label, value),
                        value -> manager.setSettingValue(setting, value), null);
            }
            packSettings.add(component);
        }
        for (int i = 0; i < packSettings.size(); i += 2) {
            content.add(i + 1 < packSettings.size()
                    ? new HStack(4, packSettings.get(i), packSettings.get(i + 1))
                    : new HStack(4, packSettings.get(i)));
        }
    }

    private static Slider slider(double min, double max, double value, String key, DoubleConsumer change) {
        return new Slider(CELL_W, CELL_H, min, max, 1, value,
                v -> I18n.tr(key, (int) v), change, null);
    }

    @Override
    public void onClose() {
        SkyEngine.get().getPostProcessor().getSettings().save();
        SkyEngine.get().getShaderPackManager().saveSettings();
        GameSettings.get().save();
    }
}
