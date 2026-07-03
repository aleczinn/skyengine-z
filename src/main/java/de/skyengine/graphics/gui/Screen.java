package de.skyengine.graphics.gui;

/**
 * Basis eines geöffneten GUI-Bildschirms (Truhe, Werkbank, Inventar, ...). Solange ein Screen offen
 * ist, pausiert {@code GameContainer} Maus-Blick, Block-Interaktion und Spielerbewegung; der
 * {@link GuiManager} zeigt den Cursor und leitet Klicks/Schließen hierher.
 *
 * <p>Koordinaten kommen im <b>virtuellen</b> GUI-Raum (bereits GUI-skaliert); der Screen holt sich
 * {@link GuiManager#sprites()}, {@link GuiManager#icons()}, {@link GuiManager#textures()} sowie
 * {@link GuiManager#vWidth()}/{@link GuiManager#vHeight()}.
 */
public abstract class Screen {

    public abstract void render(GuiManager gui, double mouseX, double mouseY);

    /** Mausklick (virtuelle Koordinaten). button: GLFW-Maustaste. */
    public abstract void mouseClicked(double mouseX, double mouseY, int button);

    /** Aufräumen beim Schließen (getragenen Stapel zurücklegen, Truhendeckel schließen, ...). */
    public void onClose() {}
}
