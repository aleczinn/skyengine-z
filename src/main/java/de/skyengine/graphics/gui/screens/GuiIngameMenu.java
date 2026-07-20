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
    public boolean pausesGame() {
        return true;
    }

    @Override
    public void init(GuiManager gui, float vW, float vH) {
        this.components.clear();

        Label title = new Label("Spiel pausiert", 14).measure(gui);
        Button resume = new Button("Zurück zum Spiel", gui::close);
        Button options = new Button("Optionen", () -> gui.open(new GuiOptionsMenu(this)));
        Button toTitle = new Button("Hauptmenü", () -> SkyEngine.get().getGame().exitToTitle());
        Button quit = new Button("Spiel beenden", () -> SkyEngine.get().shutdown());

        this.components.add(title);
        this.components.add(resume);
        this.components.add(options);
        this.components.add(toTitle);
        this.components.add(quit);

        VStack stack = new VStack(8).add(title).add(resume).add(options).add(toTitle).add(quit);
        stack.layoutAnchored(vW, vH, Anchor.CENTER, 0, 0);
    }
}
