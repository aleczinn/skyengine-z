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
import de.skyengine.graphics.post.PostProcessingSettings.AntiAliasingMode;
import de.skyengine.graphics.shaderpack.ShaderPackManager;
import de.skyengine.graphics.shaderpack.ShaderPackManager.PackOption;

import java.util.List;
import java.util.function.DoubleConsumer;

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
        Slider vibrance = slider(-100, 100, post.getVibrance() * 100,
                "options.shaderpacks.vibrance", value -> post.setVibrance((float) value / 100F));
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
    }

    private static Slider slider(double min, double max, double value, String key, DoubleConsumer change) {
        return new Slider(CELL_W, CELL_H, min, max, 1, value,
                v -> I18n.tr(key, (int) v), change, null);
    }

    @Override
    public void onClose() {
        SkyEngine.get().getPostProcessor().getSettings().save();
        GameSettings.get().save();
    }
}
