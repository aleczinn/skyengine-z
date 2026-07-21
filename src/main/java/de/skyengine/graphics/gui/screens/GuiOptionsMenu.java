package de.skyengine.graphics.gui.screens;

import de.skyengine.core.SkyEngine;
import de.skyengine.core.settings.GameSettings;
import de.skyengine.game.GameContainer;
import de.skyengine.graphics.gui.GuiManager;
import de.skyengine.graphics.gui.GuiScreen;
import de.skyengine.graphics.gui.layout.Anchor;
import de.skyengine.graphics.gui.layout.HStack;
import de.skyengine.graphics.gui.layout.VStack;
import de.skyengine.graphics.gui.widget.Button;
import de.skyengine.graphics.gui.widget.CycleButton;
import de.skyengine.graphics.gui.widget.Label;
import de.skyengine.graphics.gui.widget.Slider;
import de.skyengine.graphics.gui.widget.Spacer;

/**
 * Optionsmenü (allgemein): FOV, GUI-Größe, Sensitivität, VSync, Lautstärken. Grafik-Einstellungen
 * liegen auf der Unterseite {@link GuiVideoSettings} (zwei Spalten passen nicht komplett in die
 * virtuelle Höhe bei 720p). Erreichbar aus Pause- und (später) Titel-Menü; speichert beim Verlassen.
 */
public final class GuiOptionsMenu extends GuiScreen {

    /* Breite/Höhe einer Options-Zelle (zweispaltig, MC-Maß). */
    static final float CELL_W = 150, CELL_H = 20;

    private final GameSettings settings = GameSettings.get();

    public GuiOptionsMenu(GuiScreen parent) {
        super(parent);
    }

    @Override
    public boolean pausesGame() {
        return this.parent != null && this.parent.pausesGame();
    }

    @Override
    public boolean blursBackground() {
        return this.parent != null && this.parent.blursBackground();
    }

    @Override
    public void init(GuiManager gui, float vW, float vH) {
        this.components.clear();
        GameContainer game = SkyEngine.get().getGame();

        Label title = new Label("Optionen", 14).measure(gui);

        Slider fov = new Slider(CELL_W, CELL_H, 30, 120, 1, this.settings.fov,
                v -> "FOV: " + (int) v,
                v -> {
                    this.settings.fov = (int) v;
                    game.getCamera().setFov((int) v);
                }, null);

        /* GUI-Größe erst beim Loslassen anwenden: setScale layoutet den GuiScreen neu
           und würde einen laufenden Drag abbrechen. */
        Slider guiScale = new Slider(CELL_W, CELL_H, 30, 170, 5, this.settings.guiScalePercent,
                v -> "GUI-Größe: " + (int) v + " %",
                v -> this.settings.guiScalePercent = (int) v,
                () -> gui.setScale(this.settings.guiScaleFactor()));

        Slider sensitivity = new Slider(CELL_W, CELL_H, 10, 300, 5, this.settings.mouseSensitivity * 100,
                v -> "Sensitivität: " + (int) v + " %",
                v -> this.settings.mouseSensitivity = v / 100.0, null);

        CycleButton<Boolean> vsync = CycleButton.onOff("VSync", CELL_W, CELL_H, this.settings.vsync, v -> {
            this.settings.vsync = v;
            /* Läuft auf dem Render-Thread — glfwSwapInterval gehört genau dorthin. */
            SkyEngine.get().getWindow().setVsync(v);
        });

        Slider master = new Slider(CELL_W, CELL_H, 0, 100, 5, this.settings.masterVolume,
                v -> "Lautstärke: " + (int) v + " %",
                v -> {
                    this.settings.masterVolume = (int) v;
                    game.applyAudioSettings();
                }, null);

        Slider music = new Slider(CELL_W, CELL_H, 0, 100, 5, this.settings.musicVolume,
                v -> "Musik: " + (int) v + " %",
                v -> {
                    this.settings.musicVolume = (int) v;
                    game.applyAudioSettings();
                }, null);

        /* Drei einzelne Punkte statt "…" (U+2026) — der Font-Atlas hat die Ellipse nicht. */
        Button video = new Button("Grafik...", CELL_W, CELL_H, () -> gui.open(new GuiVideoSettings(this)));
        Button keybinds = new Button("Tastenbelegung...", CELL_W, CELL_H, () -> gui.open(new GuiKeybinds(this)));
        Button done = new Button("Fertig", () -> this.goBack(gui));

        /* MC-Layout: Titel weit oben, Inhalt im oberen Drittel angedockt. */
        VStack content = new VStack(4,
                new HStack(4, fov, guiScale),
                new HStack(4, sensitivity, vsync),
                new HStack(4, master, music),
                new HStack(4, video, keybinds),
                new Spacer(0, 8),
                done);
        this.components.add(title.anchor(Anchor.TOP_CENTER, 0, titleTop(vH)));
        this.components.add(content.anchor(Anchor.TOP_CENTER, 0, contentTop(vH, content.height())));
    }

    @Override
    public void onClose() {
        this.settings.save();
    }
}
