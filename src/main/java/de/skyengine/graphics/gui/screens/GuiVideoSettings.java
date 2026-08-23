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
import de.skyengine.graphics.post.PostProcessingSettings;

import static de.skyengine.graphics.gui.screens.GuiOptionsMenu.CELL_H;
import static de.skyengine.graphics.gui.screens.GuiOptionsMenu.CELL_W;

/**
 * Grafik-Unterseite des Optionsmenüs: Distanzen, MSAA/Anisotropie (greifen erst beim nächsten
 * Framebuffer-/Textur-Aufbau), AO/Laub (lösen einen Voll-Remesh aus), Nebel, VSync.
 * LOD liegt auf einer eigenen Unterseite ({@link GuiLodSettings}).
 * Welt-abhängige Anwendungen sind null-geguardet (Optionen sind auch ohne Welt erreichbar).
 */
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

        /* Distanzen erst beim Loslassen anwenden (Chunk-Loading/Unload ist teuer). */
        Slider render = new Slider(CELL_W, CELL_H, 2, 32, 1, this.settings.renderDistance,
                v -> I18n.tr("options.video.render_distance", (int) v),
                v -> this.settings.renderDistance = (int) v,
                game::applySettings);

        Slider simulation = new Slider(CELL_W, CELL_H, 2, 32, 1, this.settings.simulationDistance,
                v -> I18n.tr("options.video.simulation_distance", (int) v),
                v -> this.settings.simulationDistance = (int) v,
                game::applySettings);

        CycleButton<Integer> msaa = new CycleButton<>(I18n.tr("options.video.msaa"), CELL_W, CELL_H,
                new Integer[]{0, 2, 4, 8, 16}, this.settings.msaaSamples,
                v -> v == 0 ? I18n.tr("gui.off") : v + "x",
                v -> this.settings.msaaSamples = v) // greift beim nächsten Framebuffer-Aufbau (Resize/Neustart)
                .tooltipOf(v -> I18n.tr("options.video.msaa_hint")
                        + "\n" + I18n.tr(v == 0 ? "options.video.msaa_off" : "options.video.msaa_on", v));

        CycleButton<Integer> aniso = new CycleButton<>(I18n.tr("options.video.anisotropy"), CELL_W, CELL_H,
                new Integer[]{1, 2, 4, 8, 16}, this.settings.anisotropicFiltering,
                v -> v == 1 ? I18n.tr("gui.off") : v + "x",
                v -> this.settings.anisotropicFiltering = v); // greift beim nächsten TextureArray-Aufbau (Neustart)

        CycleButton<Boolean> ao = CycleButton.onOff(I18n.tr("options.video.ao"), CELL_W, CELL_H,
                this.settings.ambientOcclusion, v -> {
                    this.settings.ambientOcclusion = v;
                    this.remesh(game);
                });

        CycleButton<GameSettings.LeavesQuality> leaves = new CycleButton<>(I18n.tr("options.video.leaves"), CELL_W, CELL_H,
                GameSettings.LeavesQuality.values(), this.settings.leavesQuality,
                Enum::name,
                v -> {
                    this.settings.leavesQuality = v;
                    this.remesh(game);
                }).tooltipOf(v -> I18n.tr("options.video.leaves_hint_" + v.name().toLowerCase()));

        CycleButton<Boolean> fog = CycleButton.onOff(I18n.tr("options.video.fog"), CELL_W, CELL_H, this.settings.fog,
                v -> this.settings.fog = v);

        CycleButton<GameSettings.ParticleQuality> particles = new CycleButton<>(
                I18n.tr("options.video.particles"), CELL_W, CELL_H,
                GameSettings.ParticleQuality.values(), this.settings.particleQuality,
                value -> I18n.tr("options.video.particles_" + value.name().toLowerCase()),
                value -> this.settings.particleQuality = value);

        /* Wird pro Frame im ChunkRenderer gelesen (Shader-Fade) -> greift live, kein Remesh. */
        Slider vegetation = new Slider(CELL_W, CELL_H, 0, 32, 1, this.settings.vegetationDistance,
                v -> I18n.tr("options.video.vegetation_distance",
                        (int) v == 0 ? I18n.tr("gui.off") : String.valueOf((int) v)),
                v -> this.settings.vegetationDistance = (int) v, null);

        /* LOD hat eine eigene Unterseite (Ringe, Reichweite, AO-Qualitaet) — hier steht nur
           noch der Einstieg, damit die Einstellungen nicht an zwei Orten liegen. */
        Button lod = new Button(I18n.tr("options.lod.button"), CELL_W, CELL_H,
                () -> gui.open(new GuiLodSettings(this)));

        CycleButton<Boolean> vsync = CycleButton.onOff(I18n.tr("options.video.vsync"), CELL_W, CELL_H, this.settings.vsync, v -> {
            this.settings.vsync = v;
            /* Läuft auf dem Render-Thread — glfwSwapInterval gehört genau dorthin. */
            SkyEngine.get().getWindow().setVsync(v);
        });

        /* Beide greifen live (werden pro Frame in updateViewEffect gelesen). */
        CycleButton<Boolean> bobbing = CycleButton.onOff(I18n.tr("options.video.view_bobbing"), CELL_W, CELL_H,
                this.settings.viewBobbing, v -> this.settings.viewBobbing = v);

        CycleButton<Boolean> damageTilt = CycleButton.onOff(I18n.tr("options.video.damage_tilt"), CELL_W, CELL_H,
                this.settings.damageTilt, v -> this.settings.damageTilt = v);

        /* Wirkt live über u_MinLight im Chunk-Shader -> kein Remesh. Unterste Stufe ist AUS
           und bedeutet Fullbright (Himmelslicht wirkungslos, Bild wie ohne Lichtsystem). */
        Slider brightness = new Slider(CELL_W, CELL_H, 0, 100, 1, this.settings.brightness,
                v -> I18n.tr("options.video.brightness",
                        (int) v == 0 ? I18n.tr("gui.off") : (int) v + " %"),
                v -> this.settings.brightness = (int) v, null);

        PostProcessingSettings post = SkyEngine.get().getPostProcessor().getSettings();
        CycleButton<PostProcessingSettings.AntiAliasingMode> aa = new CycleButton<>(I18n.tr("options.debug.aa_mode"), CELL_W, CELL_H, PostProcessingSettings.AntiAliasingMode.values(), post.getAaMode(), Enum::name, post::setAaMode);

        content.add(new HStack(4, render, simulation));
        content.add(new HStack(4, msaa, aniso));
        content.add(new HStack(4, ao, leaves));
        content.add(new HStack(4, fog, particles));
        content.add(new HStack(4, vegetation, lod));
        content.add(new HStack(4, bobbing, damageTilt));
        content.add(new HStack(4, vsync, brightness));
        content.add(new HStack(4, aa));
    }

    /** AO/Laub stecken im gebackenen Mesh -> Voll-Remesh (nur mit Welt möglich). */
    private void remesh(GameContainer game) {
        if (game.getWorld() != null) {
            game.getWorld().getChunkManager().remeshAll();
        }
    }

    @Override
    public void onClose() {
        this.settings.save();
    }
}
