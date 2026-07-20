package de.skyengine.graphics.gui.screens;

import de.skyengine.core.SkyEngine;
import de.skyengine.graphics.color.Color4;
import de.skyengine.graphics.gui.GuiManager;
import de.skyengine.graphics.gui.Screen;
import de.skyengine.graphics.gui.layout.Anchor;
import de.skyengine.graphics.gui.layout.VStack;
import de.skyengine.graphics.gui.widget.Button;
import de.skyengine.graphics.gui.widget.Label;

/**
 * Hauptmenü (Startbildschirm): gekachelter Menü-Hintergrund, Titel, Einzelspieler / Optionen /
 * Beenden. Nicht schließbar — es gibt keine Welt, in die ESC zurückkehren könnte.
 */
public final class TitleScreen extends Screen {

    private static final Color4 VERSION_COLOR = new Color4(0.7f, 0.7f, 0.7f, 1f);

    public TitleScreen() {
        super(null);
    }

    @Override
    public boolean isClosable() {
        return false;
    }

    @Override
    public void init(GuiManager gui, float vW, float vH) {
        this.components.clear();

        Label title = new Label(SkyEngine.ENGINE_NAME, 32).measure(gui);
        Button singleplayer = new Button("Einzelspieler", () -> gui.open(new WorldSelectScreen(this)));
        Button options = new Button("Optionen", () -> gui.open(new OptionsScreen(this)));
        Button quit = new Button("Spiel beenden", () -> SkyEngine.get().shutdown());

        Label version = new Label(SkyEngine.ENGINE_NAME + " v" + SkyEngine.ENGINE_VERSION,
                8, VERSION_COLOR, true).measure(gui);
        version.layoutAt(2, vH - 10);

        this.components.add(title);
        this.components.add(singleplayer);
        this.components.add(options);
        this.components.add(quit);
        this.components.add(version);

        VStack stack = new VStack(8).add(title).add(singleplayer).add(options).add(quit);
        stack.layoutAnchored(vW, vH, Anchor.CENTER, 0, 0);
    }

    /* Hintergrund: geerbter Menü-Kachel-Default (Screen.renderBackground ohne Welt). */
}
