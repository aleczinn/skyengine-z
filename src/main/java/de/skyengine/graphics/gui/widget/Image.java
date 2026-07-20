package de.skyengine.graphics.gui.widget;

import de.skyengine.graphics.gui.GuiManager;
import de.skyengine.graphics.texture.Texture;

/**
 * Statisches Bild (nur Sprite-Pass): Zielbreite wird vorgegeben, die Höhe folgt dem
 * Seitenverhältnis der Textur — nie verzerrt. Für Logos/Illustrationen in Stacks.
 */
public final class Image extends GuiComponent {

    private final Texture texture;

    public Image(Texture texture, float width) {
        this.texture = texture;
        this.w = width;
        this.h = width * texture.getHeight() / (float) texture.getWidth();
    }

    @Override
    public void renderBackground(GuiManager gui, double mx, double my) {
        gui.sprites().drawSprite(this.texture, this.x, this.y, this.w, this.h);
    }
}
