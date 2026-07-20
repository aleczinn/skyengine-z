package de.skyengine.graphics.gui.screens;

import de.skyengine.core.SkyEngine;
import de.skyengine.core.settings.GameSettings;
import de.skyengine.game.GameContainer;
import de.skyengine.graphics.gui.GuiManager;
import de.skyengine.graphics.gui.Screen;
import de.skyengine.graphics.gui.layout.Anchor;
import de.skyengine.graphics.gui.layout.HStack;
import de.skyengine.graphics.gui.layout.VStack;
import de.skyengine.graphics.gui.widget.Button;
import de.skyengine.graphics.gui.widget.CycleButton;
import de.skyengine.graphics.gui.widget.Label;
import de.skyengine.graphics.gui.widget.Slider;

import static de.skyengine.graphics.gui.screens.OptionsScreen.CELL_H;
import static de.skyengine.graphics.gui.screens.OptionsScreen.CELL_W;

/**
 * Grafik-Unterseite des Optionsmenüs: Distanzen, MSAA/Anisotropie (greifen erst beim nächsten
 * Framebuffer-/Textur-Aufbau), AO/Laub (lösen einen Voll-Remesh aus), Nebel, LOD.
 * Welt-abhängige Anwendungen sind null-geguardet (Optionen sind auch ohne Welt erreichbar).
 */
public final class VideoSettingsScreen extends Screen {

    private final GameSettings settings = GameSettings.get();

    public VideoSettingsScreen(Screen parent) {
        super(parent);
    }

    @Override
    public boolean pausesGame() {
        return this.parent != null && this.parent.pausesGame();
    }

    @Override
    public void init(GuiManager gui, float vW, float vH) {
        this.components.clear();
        GameContainer game = SkyEngine.get().getGame();

        Label title = new Label("Grafik", 14).measure(gui);

        /* Distanzen erst beim Loslassen anwenden (Chunk-Loading/Unload ist teuer). */
        Slider render = new Slider(CELL_W, CELL_H, 2, 32, 1, this.settings.renderDistance,
                v -> "Render-Distanz: " + (int) v,
                v -> this.settings.renderDistance = (int) v,
                game::applySettings);

        Slider simulation = new Slider(CELL_W, CELL_H, 2, 32, 1, this.settings.simulationDistance,
                v -> "Simulations-Distanz: " + (int) v,
                v -> this.settings.simulationDistance = (int) v,
                game::applySettings);

        CycleButton<Integer> msaa = new CycleButton<>("MSAA", CELL_W, CELL_H,
                new Integer[]{0, 2, 4, 8, 16}, this.settings.msaaSamples,
                v -> v == 0 ? "AUS" : v + "x",
                v -> this.settings.msaaSamples = v); // greift beim nächsten Framebuffer-Aufbau (Resize/Neustart)

        CycleButton<Integer> aniso = new CycleButton<>("Anisotropie", CELL_W, CELL_H,
                new Integer[]{1, 2, 4, 8, 16}, this.settings.anisotropicFiltering,
                v -> v == 1 ? "AUS" : v + "x",
                v -> this.settings.anisotropicFiltering = v); // greift beim nächsten TextureArray-Aufbau (Neustart)

        CycleButton<Boolean> ao = CycleButton.onOff("Ambient Occlusion", CELL_W, CELL_H,
                this.settings.ambientOcclusion, v -> {
                    this.settings.ambientOcclusion = v;
                    this.remesh(game);
                });

        CycleButton<GameSettings.LeavesQuality> leaves = new CycleButton<>("Laub", CELL_W, CELL_H,
                GameSettings.LeavesQuality.values(), this.settings.leavesQuality,
                Enum::name,
                v -> {
                    this.settings.leavesQuality = v;
                    this.remesh(game);
                });

        CycleButton<Boolean> fog = CycleButton.onOff("Nebel", CELL_W, CELL_H, this.settings.fog,
                v -> this.settings.fog = v);

        /* Wird pro Frame im ChunkRenderer gelesen (Shader-Fade) -> greift live, kein Remesh. */
        Slider vegetation = new Slider(CELL_W, CELL_H, 0, 32, 1, this.settings.vegetationDistance,
                v -> "Vegetations-Distanz: " + ((int) v == 0 ? "AUS" : String.valueOf((int) v)),
                v -> this.settings.vegetationDistance = (int) v, null);

        CycleButton<Boolean> lod = CycleButton.onOff("LOD (Fernsicht)", CELL_W, CELL_H,
                this.settings.lodEnabled, v -> {
                    this.settings.lodEnabled = v;
                    game.applySettings(); // farPlane nachziehen; LodManager liest das Setting selbst
                });

        Slider lodDistance = new Slider(CELL_W, CELL_H, 8, 512, 8, this.settings.lodMaxDistance,
                v -> "LOD-Distanz: " + (int) v,
                v -> this.settings.lodMaxDistance = (int) v,
                game::applySettings);

        Button done = new Button("Fertig", () -> this.goBack(gui));

        this.components.add(title);
        this.components.add(render);
        this.components.add(simulation);
        this.components.add(msaa);
        this.components.add(aniso);
        this.components.add(ao);
        this.components.add(leaves);
        this.components.add(fog);
        this.components.add(vegetation);
        this.components.add(lod);
        this.components.add(lodDistance);
        this.components.add(done);

        VStack stack = new VStack(4)
                .add(title)
                .add(new HStack(4).add(render).add(simulation))
                .add(new HStack(4).add(msaa).add(aniso))
                .add(new HStack(4).add(ao).add(leaves))
                .add(new HStack(4).add(fog).add(vegetation))
                .add(new HStack(4).add(lod).add(lodDistance))
                .add(done);
        stack.layoutAnchored(vW, vH, Anchor.CENTER, 0, 0);
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
