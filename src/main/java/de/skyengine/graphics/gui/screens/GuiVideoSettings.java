package de.skyengine.graphics.gui.screens;

import de.skyengine.core.SkyEngine;
import de.skyengine.core.i18n.I18n;
import de.skyengine.core.settings.GameSettings;
import de.skyengine.game.GameContainer;
import de.skyengine.graphics.gui.GuiManager;
import de.skyengine.graphics.gui.GuiScreen;
import de.skyengine.graphics.gui.layout.HStack;
import de.skyengine.graphics.gui.layout.VStack;
import de.skyengine.graphics.gui.widget.Button;
import de.skyengine.graphics.gui.widget.CycleButton;
import de.skyengine.graphics.gui.widget.Slider;
import de.skyengine.graphics.post.PostProcessingSettings.AntiAliasingMode;

import static de.skyengine.graphics.gui.screens.GuiOptionsMenu.CELL_H;
import static de.skyengine.graphics.gui.screens.GuiOptionsMenu.CELL_W;

/** Grafik-Unterseite des Optionsmenüs. */
public final class GuiVideoSettings extends GuiOptionsScreen {

    private final GameSettings settings = GameSettings.get();

    public GuiVideoSettings(GuiScreen parent) {
        super(parent);
    }

    @Override
    protected String title() {
        return I18n.tr("options.video.title");
    }

    @Override
    protected void buildContent(GuiManager gui, VStack content) {
        GameContainer game = SkyEngine.get().getGame();

        Slider render = new Slider(CELL_W, CELL_H, 2, 32, 1, this.settings.renderDistance,
                v -> I18n.tr("options.video.render_distance", (int) v),
                v -> this.settings.renderDistance = (int) v, game::applySettings);
        Slider simulation = new Slider(CELL_W, CELL_H, 2, 32, 1, this.settings.simulationDistance,
                v -> I18n.tr("options.video.simulation_distance", (int) v),
                v -> this.settings.simulationDistance = (int) v, game::applySettings);

        var post = SkyEngine.get().getPostProcessor().getSettings();
        CycleButton<AntiAliasingMode> aa = new CycleButton<>(I18n.tr("options.video.aa"), CELL_W, CELL_H,
                AntiAliasingMode.values(), post.getAaMode(),
                mode -> I18n.tr("options.video.aa_" + mode.name().toLowerCase()), post::setAaMode);
        CycleButton<Integer> aniso = new CycleButton<>(I18n.tr("options.video.anisotropy"), CELL_W, CELL_H,
                new Integer[]{1, 2, 4, 8, 16}, this.settings.anisotropicFiltering,
                v -> v == 1 ? I18n.tr("gui.off") : v + "x",
                v -> this.settings.anisotropicFiltering = v);

        CycleButton<Boolean> ao = CycleButton.onOff(I18n.tr("options.video.ao"), CELL_W, CELL_H,
                this.settings.ambientOcclusion, v -> {
                    this.settings.ambientOcclusion = v;
                    this.remesh(game);
                });
        CycleButton<GameSettings.LeavesQuality> leaves = new CycleButton<>(I18n.tr("options.video.leaves"), CELL_W, CELL_H,
                GameSettings.LeavesQuality.values(), this.settings.leavesQuality, Enum::name, v -> {
                    this.settings.leavesQuality = v;
                    this.remesh(game);
                }).tooltipOf(v -> I18n.tr("options.video.leaves_hint_" + v.name().toLowerCase()));
        CycleButton<Boolean> fog = CycleButton.onOff(I18n.tr("options.video.fog"), CELL_W, CELL_H,
                this.settings.fog, v -> this.settings.fog = v);
        Slider vegetation = new Slider(CELL_W, CELL_H, 0, 32, 1, this.settings.vegetationDistance,
                v -> I18n.tr("options.video.vegetation_distance",
                        (int) v == 0 ? I18n.tr("gui.off") : String.valueOf((int) v)),
                v -> this.settings.vegetationDistance = (int) v, null);
        CycleButton<Boolean> lod = CycleButton.onOff(I18n.tr("options.video.lod"), CELL_W, CELL_H,
                this.settings.lodEnabled, v -> {
                    this.settings.lodEnabled = v;
                    game.applySettings();
                });
        Slider lodDistance = new Slider(CELL_W, CELL_H, 8, 256, 8, this.settings.lodMaxDistance,
                v -> I18n.tr("options.video.lod_distance", (int) v),
                v -> this.settings.lodMaxDistance = (int) v, game::applySettings);
        CycleButton<Boolean> vsync = CycleButton.onOff(I18n.tr("options.video.vsync"), CELL_W, CELL_H,
                this.settings.vsync, v -> {
                    this.settings.vsync = v;
                    SkyEngine.get().getWindow().setVsync(v);
                });
        CycleButton<Boolean> bobbing = CycleButton.onOff(I18n.tr("options.video.view_bobbing"), CELL_W, CELL_H,
                this.settings.viewBobbing, v -> this.settings.viewBobbing = v);
        CycleButton<Boolean> damageTilt = CycleButton.onOff(I18n.tr("options.video.damage_tilt"), CELL_W, CELL_H,
                this.settings.damageTilt, v -> this.settings.damageTilt = v);
        Slider brightness = new Slider(CELL_W, CELL_H, 0, 100, 1, this.settings.brightness,
                v -> I18n.tr("options.video.brightness",
                        (int) v == 0 ? I18n.tr("gui.off") : (int) v + " %"),
                v -> this.settings.brightness = (int) v, null);
        Button shaderPacks = new Button(I18n.tr("options.shaderpacks.button"), CELL_W, CELL_H,
                () -> gui.open(new GuiShaderPacks(this)));

        content.add(new HStack(4, render, simulation));
        content.add(new HStack(4, aa, aniso));
        content.add(new HStack(4, ao, leaves));
        content.add(new HStack(4, fog, vegetation));
        content.add(new HStack(4, lod, lodDistance));
        content.add(new HStack(4, bobbing, damageTilt));
        content.add(new HStack(4, vsync, brightness));
        content.add(new HStack(4, shaderPacks));
    }

    private void remesh(GameContainer game) {
        if (game.getWorld() != null) game.getWorld().getChunkManager().remeshAll();
    }

    @Override
    public void onClose() {
        this.settings.save();
        SkyEngine.get().getPostProcessor().getSettings().save();
    }
}
