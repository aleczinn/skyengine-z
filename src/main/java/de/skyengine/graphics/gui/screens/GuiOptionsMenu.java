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
import de.skyengine.graphics.gui.widget.Label;
import de.skyengine.graphics.gui.widget.Slider;
import de.skyengine.graphics.gui.widget.Spacer;

/**
 * Optionsmenü (Übersicht): nur noch FOV + GUI-Größe direkt, alles andere auf Unterseiten —
 * {@link GuiSoundOptions} (Lautstärken/Gerät), {@link GuiControls} (Sensitivität/Toggles/
 * Tastenbelegung), {@link GuiVideoSettings} (Grafik inkl. VSync), {@link GuiLanguage} und
 * {@link GuiResourcePacks} (beides Platzhalter). Erreichbar aus Pause- und Titel-Menü;
 * speichert beim Verlassen.
 */
public final class GuiOptionsMenu extends GuiScreen {

    /* Breite/Höhe einer Options-Zelle (zweispaltig, MC-Maß). */
    static final float CELL_W = 150, CELL_H = 20;

    private final GameSettings settings = GameSettings.get();

    public GuiOptionsMenu(GuiScreen parent) {
        super(parent);
    }

    @Override
    public boolean doesPausesGame() {
        return this.parent != null && this.parent.doesPausesGame();
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

        /* Drei einzelne Punkte statt "…" (U+2026) — der Font-Atlas hat die Ellipse nicht. */
        Button sound = new Button("Musik & Geräusche...", CELL_W, CELL_H, () -> gui.open(new GuiSoundOptions(this)));
        Button controls = new Button("Steuerung...", CELL_W, CELL_H, () -> gui.open(new GuiControls(this)));
        Button graphics = new Button("Grafik...", CELL_W, CELL_H, () -> gui.open(new GuiVideoSettings(this)));
        Button language = new Button("Sprache...", CELL_W, CELL_H, () -> gui.open(new GuiLanguage(this)));
        Button packs = new Button("Ressourcenpakete...", CELL_W, CELL_H, () -> gui.open(new GuiResourcePacks(this)));
        Button done = new Button("Fertig", () -> this.goBack(gui));

        VStack content = new VStack(4,
                new HStack(4, fov, guiScale),
                new Spacer(0, 8),
                new HStack(4, graphics, sound),
                new HStack(4, language, controls),
                /* Leere rechte Zelle: Spacer statt null — Stacks rufen width() auf jedem Kind. */
                new HStack(4, packs, new Spacer(CELL_W, CELL_H)),
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
