package de.skyengine.graphics.gui.screens;

import de.skyengine.core.SkyEngine;
import de.skyengine.graphics.gui.GuiManager;
import de.skyengine.graphics.gui.GuiScreen;
import de.skyengine.graphics.gui.layout.Anchor;
import de.skyengine.graphics.gui.layout.VStack;
import de.skyengine.graphics.gui.widget.Button;
import de.skyengine.graphics.gui.widget.Label;
import de.skyengine.graphics.gui.widget.Spacer;

/**
 * Pause-Menü (ESC im Spiel): pausiert die Welt, solange es offen ist.
 * ESC/„Zurück zum Spiel" schließt; „Beenden" beendet die Engine.
 */
public final class GuiIngameMenu extends GuiScreen {

    public GuiIngameMenu() {
        super(null);
    }

    @Override
    public boolean pausesGame() {
        return true;
    }

    @Override
    public void init(GuiManager gui, float vW, float vH) {
        this.components.clear();

        Label title = new Label("Spielmenü", 14).measure(gui);
        Button resume = new Button("Zurück zum Spiel", gui::close);
        Button options = new Button("Optionen", () -> gui.open(new GuiOptionsMenu(this)));
        Button toTitle = new Button("Speichern und zurück zum Hauptmenü", () -> SkyEngine.get().getGame().exitToTitle());

        this.components.add(new VStack(8,
                title,
                new Spacer(0, 8),
                resume,
                options,
                toTitle)
                .anchor(Anchor.CENTER));
    }
}
