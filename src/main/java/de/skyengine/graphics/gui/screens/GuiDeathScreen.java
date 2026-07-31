package de.skyengine.graphics.gui.screens;

import de.skyengine.core.SkyEngine;
import de.skyengine.core.i18n.I18n;
import de.skyengine.graphics.gui.GuiManager;
import de.skyengine.graphics.gui.GuiScreen;
import de.skyengine.graphics.gui.layout.Anchor;
import de.skyengine.graphics.gui.layout.VStack;
import de.skyengine.graphics.gui.widget.Button;
import de.skyengine.graphics.gui.widget.Label;
import de.skyengine.graphics.gui.GuiText;

/**
 * Todesscreen (Survival): rotes Overlay über der weiterlaufenden Welt (pausiert NICHT, wie MC),
 * Respawn am Weltspawn oder zurück zum Hauptmenü. Nicht schließbar — ESC führt nicht zurück
 * ins Spiel, tot bleibt tot bis zur Entscheidung. Das Inventar bleibt beim Respawn erhalten
 * (User-Entscheid, kein Item-Drop).
 */
public final class GuiDeathScreen extends GuiScreen {

    public GuiDeathScreen() {
        super(null);
    }

    @Override
    public boolean isClosable() {
        return false;
    }

    @Override
    public void init(GuiManager gui, float vW, float vH) {
        this.components.clear();

        Label title = new Label(I18n.tr("death.title"), GuiText.LARGE).measure(gui);
        Button respawn = new Button(I18n.tr("death.respawn"), () -> SkyEngine.get().getGame().respawnPlayer());
        Button toTitle = new Button(I18n.tr("death.main_menu"), () -> SkyEngine.get().getGame().exitToTitle());

        VStack content = new VStack(4, respawn, toTitle);
        this.components.add(title.anchor(Anchor.TOP_CENTER, 0, titleTop(vH)));
        this.components.add(content.anchor(Anchor.CENTER, 0, contentTop(vH, content.height())));
    }

    /** MC-Look: tiefrotes Halbtransparenz-Overlay statt des grauen Standard-Dims. */
    @Override
    protected void renderBackground(GuiManager gui) {
        gui.sprites().drawRect(0, 0, gui.vWidth(), gui.vHeight(), 0.4f, 0f, 0f, 0.5f);
    }
}
