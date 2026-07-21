package de.skyengine.graphics.gui.screens;

import de.skyengine.core.SkyEngine;
import de.skyengine.graphics.color.Color4;
import de.skyengine.graphics.gui.GuiManager;
import de.skyengine.graphics.gui.GuiScreen;
import de.skyengine.graphics.gui.layout.Anchor;
import de.skyengine.graphics.gui.layout.HStack;
import de.skyengine.graphics.gui.layout.VStack;
import de.skyengine.graphics.gui.widget.Button;
import de.skyengine.graphics.gui.widget.GuiComponent;
import de.skyengine.graphics.gui.widget.Image;
import de.skyengine.graphics.gui.widget.Label;

/**
 * Hauptmenü (Startbildschirm): gekachelter Menü-Hintergrund, Titel, Einzelspieler / Optionen /
 * Beenden. Nicht schließbar — es gibt keine Welt, in die ESC zurückkehren könnte.
 */
public final class GuiMainMenu extends GuiScreen {

    private static final Color4 VERSION_COLOR = new Color4(0.7f, 0.7f, 0.7f, 1f);

    public GuiMainMenu() {
        super(null);
    }

    @Override
    public boolean isClosable() {
        return false;
    }

    @Override
    public void init(GuiManager gui, float vW, float vH) {
        this.components.clear();

        /* Logo-Bild, wenn vorhanden — sonst Text-Titel als Fallback. */
        GuiComponent title = gui.textures().logo != null
                ? new Image(gui.textures().logo, 192)
                : new Label(SkyEngine.ENGINE_NAME, 32).measure(gui);
        Button singleplayer = new Button("Einzelspieler", () -> gui.open(new GuiSelectWorld(this)));
        /* Nebeneinander wie in MC: 98 + 4 + 98 = 200 = Breite des Einzelspieler-Buttons. */
        Button options = new Button("Optionen", 98, 20, () -> gui.open(new GuiOptionsMenu(this)));
        Button quit = new Button("Spiel beenden", 98, 20, () -> SkyEngine.get().shutdown());

        Label version = new Label(SkyEngine.ENGINE_NAME + " v" + SkyEngine.ENGINE_VERSION, 8, VERSION_COLOR, true).measure(gui);
        Label copyright = new Label("Copyright", 8, VERSION_COLOR, true).measure(gui);

        /* Logo fest oben (wie die Menü-Titel), Buttons bleiben mittig. */
        this.components.add(title.anchor(Anchor.TOP_CENTER, 0, titleTop(vH)));
        this.components.add(new VStack(8,
                singleplayer,
                new HStack(4, options, quit)
        ).anchor(Anchor.CENTER));
        this.components.add(version.anchor(Anchor.BOTTOM_LEFT, 2, 2));
        this.components.add(copyright.anchor(Anchor.BOTTOM_RIGHT, 2, 2));
    }

    /** Hauptmenü: Hintergrundbild UNGEDIMMT (object-cover); ohne Bild der Kachel-Fallback. */
    @Override
    protected void renderBackground(GuiManager gui) {
        if (!this.drawMenuImage(gui)) {
            this.drawMenuTiles(gui);
        }
    }
}
