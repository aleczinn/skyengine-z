package de.skyengine.graphics.gui.widget;

import de.skyengine.graphics.color.Color4;
import de.skyengine.graphics.gui.GuiManager;
import de.skyengine.graphics.gui.font.FontRenderer;
import de.skyengine.graphics.texture.Texture;
import org.lwjgl.glfw.GLFW;

/**
 * Standard-Schaltfläche im MC-Look ({@code widget/button.png} als 9-Slice, Rand 3 px).
 * Löst {@code onPress} beim Linksklick aus.
 */
public class Button extends GuiComponent {

    public static final float DEFAULT_WIDTH = 200;
    public static final float DEFAULT_HEIGHT = 20;
    protected static final float TEXT_SIZE = 10;

    private static final Color4 TEXT_COLOR = new Color4(1f, 1f, 1f, 1f);
    private static final Color4 TEXT_DISABLED = new Color4(0.63f, 0.63f, 0.63f, 1f);

    protected String label;
    private final Runnable onPress;

    public Button(String label, Runnable onPress) {
        this(label, DEFAULT_WIDTH, DEFAULT_HEIGHT, onPress);
    }

    public Button(String label, float w, float h, Runnable onPress) {
        this.label = label;
        this.w = w;
        this.h = h;
        this.onPress = onPress;
    }

    public void setLabel(String label) {
        this.label = label;
    }

    @Override
    public void renderBackground(GuiManager gui, double mx, double my) {
        Texture tex = !this.enabled ? gui.textures().buttonDisabled
                : this.hovered ? gui.textures().buttonHighlighted
                : gui.textures().button;
        gui.sprites().drawNineSlice(tex, this.x, this.y, this.w, this.h, 3);
    }

    @Override
    public void renderText(GuiManager gui, double mx, double my) {
        FontRenderer font = gui.font();
        float tx = this.x + (this.w - font.getStringWidth(this.label, TEXT_SIZE)) / 2f;
        float ty = this.y + (this.h - font.lineHeight(TEXT_SIZE)) / 2f;
        font.drawStringWithShadow(this.label, tx, ty, TEXT_SIZE, this.textColor());
    }

    protected Color4 textColor() {
        return this.enabled ? TEXT_COLOR : TEXT_DISABLED;
    }

    @Override
    public boolean mousePressed(double mx, double my, int button) {
        if (!this.enabled || button != GLFW.GLFW_MOUSE_BUTTON_LEFT || !this.isMouseOver(mx, my)) {
            return false;
        }
        this.onPress();
        return true;
    }

    /** Für Unterklassen mit eigener Klick-Logik (z.B. CycleButton) überschreibbar. */
    protected void onPress() {
        if (this.onPress != null) this.onPress.run();
    }
}
