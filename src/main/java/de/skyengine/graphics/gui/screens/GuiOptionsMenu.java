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
import de.skyengine.graphics.gui.widget.Slider;
import de.skyengine.graphics.gui.widget.Spacer;

/**
 * Optionsmenü (Übersicht): nur noch FOV + GUI-Größe direkt, alles andere auf Unterseiten —
 * {@link GuiSoundOptions} (Lautstärken/Gerät), {@link GuiControls} (Sensitivität/Toggles/
 * Tastenbelegung), {@link GuiVideoSettings} (Grafik inkl. VSync), {@link GuiLanguage} und
 * {@link GuiResourcePacks} (beides Platzhalter). Erreichbar aus Pause- und Titel-Menü;
 * speichert beim Verlassen.
 */
public final class GuiOptionsMenu extends GuiOptionsScreen {

    /* Breite/Höhe einer Options-Zelle (zweispaltig, MC-Maß). */
    static final float CELL_W = 150, CELL_H = 20;

    private final GameSettings settings = GameSettings.get();

    public GuiOptionsMenu(GuiScreen parent) {
        super(parent);
    }

    @Override
    protected String title() {
        return I18n.tr("options.title");
    }

    @Override
    protected void buildContent(GuiManager gui, VStack content) {
        GameContainer game = SkyEngine.get().getGame();

        Slider fov = new Slider(CELL_W, CELL_H, 30, 120, 1, this.settings.fov,
                v -> I18n.tr("options.fov", (int) v),
                v -> {
                    this.settings.fov = (int) v;
                    game.getCamera().setFov((int) v);
                }, null);

        /* GUI-Größe erst beim Loslassen anwenden: setScale layoutet den GuiScreen neu
           und würde einen laufenden Drag abbrechen. */
        Slider guiScale = new Slider(CELL_W, CELL_H, 30, 170, 5, this.settings.guiScalePercent,
                v -> I18n.tr("options.gui_scale", (int) v),
                v -> this.settings.guiScalePercent = (int) v,
                () -> gui.setScale(this.settings.guiScaleFactor()));

        /* Die "..." stehen in den Sprachdateien (drei Punkte — der Font-Atlas hat kein U+2026). */
        Button sound = new Button(I18n.tr("options.sound.button"), CELL_W, CELL_H, () -> gui.open(new GuiSoundOptions(this)));
        Button controls = new Button(I18n.tr("options.controls.button"), CELL_W, CELL_H, () -> gui.open(new GuiControls(this)));
        Button graphics = new Button(I18n.tr("options.video.button"), CELL_W, CELL_H, () -> gui.open(new GuiVideoSettings(this)));
        Button language = new Button(I18n.tr("options.language"), CELL_W, CELL_H, () -> gui.open(new GuiLanguage(this)));
        Button packs = new Button(I18n.tr("options.resourcepacks"), CELL_W, CELL_H, () -> gui.open(new GuiResourcePacks(this)));

        content.add(new HStack(4, fov, guiScale));
        content.add(new Spacer(0, 8));
        content.add(new HStack(4, graphics, sound));
        content.add(new HStack(4, language, controls));
        /* Leere rechte Zelle: Spacer statt null — Stacks rufen width() auf jedem Kind. */
        content.add(new HStack(4, packs, new Spacer(CELL_W, CELL_H)));
    }

    @Override
    public void onClose() {
        this.settings.save();
    }
}
