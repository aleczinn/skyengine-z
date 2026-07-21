package de.skyengine.graphics.gui.screens;

import de.skyengine.core.SkyEngine;
import de.skyengine.graphics.gui.GuiManager;
import de.skyengine.graphics.gui.GuiScreen;
import de.skyengine.graphics.gui.layout.Anchor;
import de.skyengine.graphics.gui.layout.VStack;
import de.skyengine.graphics.gui.widget.Button;
import de.skyengine.graphics.gui.widget.Label;

/**
 * Pause-Menü (ESC im Spiel): pausiert die Welt, solange es offen ist.
 * ESC/„Zurück zum Spiel" schließt; „Beenden" beendet die Engine.
 */
public final class GuiIngameMenu extends GuiScreen {

    public GuiIngameMenu() {
        super(null);
    }

    @Override
    public boolean doesPausesGame() {
        return true;
    }

    @Override
    public boolean blursBackground() {
        return true;
    }

    @Override
    public void init(GuiManager gui, float vW, float vH) {
        this.components.clear();

        Label title = new Label("Spielmenü", 14).measure(gui);
        Button resume = new Button("Zurück zum Spiel", gui::close);
        Button options = new Button("Optionen", () -> gui.open(new GuiOptionsMenu(this)));
        Button toTitle = new Button("Speichern und zurück zum Hauptmenü", () -> SkyEngine.get().getGame().exitToTitle());

        /* MC-Layout: Titel weit oben, Buttons im oberen Drittel angedockt. */
        VStack content = new VStack(8, resume, options, toTitle);
        this.components.add(title.anchor(Anchor.TOP_CENTER, 0, titleTop(vH)));
        this.components.add(content.anchor(Anchor.TOP_CENTER, 0, contentTop(vH, content.height())));
    }
}
