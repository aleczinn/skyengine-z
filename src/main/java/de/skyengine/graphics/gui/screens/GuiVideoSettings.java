package de.skyengine.graphics.gui.screens;

import de.skyengine.core.SkyEngine;
import de.skyengine.core.i18n.I18n;
import de.skyengine.core.settings.GameSettings;
import de.skyengine.game.GameContainer;
import de.skyengine.graphics.gui.GuiManager;
import de.skyengine.graphics.gui.GuiScreen;
import de.skyengine.graphics.gui.layout.HStack;
import de.skyengine.graphics.gui.layout.VStack;
import de.skyengine.graphics.gui.widget.CycleButton;
import de.skyengine.graphics.gui.widget.Slider;

import static de.skyengine.graphics.gui.screens.GuiOptionsMenu.CELL_H;
import static de.skyengine.graphics.gui.screens.GuiOptionsMenu.CELL_W;

/**
 * Grafik-Unterseite des Optionsmenüs: Distanzen, MSAA/Anisotropie (greifen erst beim nächsten
 * Framebuffer-/Textur-Aufbau), AO/Laub (lösen einen Voll-Remesh aus), Nebel, LOD, VSync.
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
                v -> this.settings.msaaSamples = v); // greift beim nächsten Framebuffer-Aufbau (Resize/Neustart)

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
                });

        CycleButton<Boolean> fog = CycleButton.onOff(I18n.tr("options.video.fog"), CELL_W, CELL_H, this.settings.fog,
                v -> this.settings.fog = v);

        /* Wird pro Frame im ChunkRenderer gelesen (Shader-Fade) -> greift live, kein Remesh. */
        Slider vegetation = new Slider(CELL_W, CELL_H, 0, 32, 1, this.settings.vegetationDistance,
                v -> I18n.tr("options.video.vegetation_distance",
                        (int) v == 0 ? I18n.tr("gui.off") : String.valueOf((int) v)),
                v -> this.settings.vegetationDistance = (int) v, null);

        CycleButton<Boolean> lod = CycleButton.onOff(I18n.tr("options.video.lod"), CELL_W, CELL_H,
                this.settings.lodEnabled, v -> {
                    this.settings.lodEnabled = v;
                    game.applySettings(); // farPlane nachziehen; LodManager liest das Setting selbst
                });

        Slider lodDistance = new Slider(CELL_W, CELL_H, 8, 256, 8, this.settings.lodMaxDistance,
                v -> I18n.tr("options.video.lod_distance", (int) v),
                v -> this.settings.lodMaxDistance = (int) v,
                game::applySettings);

        CycleButton<Boolean> vsync = CycleButton.onOff(I18n.tr("options.video.vsync"), CELL_W, CELL_H, this.settings.vsync, v -> {
            this.settings.vsync = v;
            /* Läuft auf dem Render-Thread — glfwSwapInterval gehört genau dorthin. */
            SkyEngine.get().getWindow().setVsync(v);
        });

        content.add(new HStack(4, render, simulation));
        content.add(new HStack(4, msaa, aniso));
        content.add(new HStack(4, ao, leaves));
        content.add(new HStack(4, fog, vegetation));
        content.add(new HStack(4, lod, lodDistance));
        content.add(vsync);
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
