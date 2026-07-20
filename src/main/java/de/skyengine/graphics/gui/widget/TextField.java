package de.skyengine.graphics.gui.widget;

import de.skyengine.graphics.color.Color4;
import de.skyengine.graphics.color.Colors;
import de.skyengine.graphics.gui.GuiManager;
import de.skyengine.graphics.texture.Texture;
import org.lwjgl.glfw.GLFW;

import java.util.function.IntPredicate;

/**
 * Einzeiliges Textfeld ({@code widget/text_field.png} als 9-Slice): Fokus per Klick, Zeichen
 * über die Char-Events, Editier-Tasten (Backspace/Entf/Pfeile/Pos1/Ende), blinkender Caret.
 * Bewusst ohne Selektion/Zwischenablage (Phase 1).
 */
public final class TextField extends GuiComponent {

    private static final float TEXT_SIZE = 10;
    private static final float PAD = 4;
    private static final Color4 PLACEHOLDER = new Color4(0.55f, 0.55f, 0.55f, 1f);

    private final StringBuilder text = new StringBuilder();
    private final int maxLength;
    private final IntPredicate filter; // null = alle druckbaren Zeichen
    private String placeholder = "";
    private int caret;

    public TextField(float w, float h, int maxLength, IntPredicate filter) {
        this.w = w;
        this.h = h;
        this.maxLength = maxLength;
        this.filter = filter;
    }

    public TextField placeholder(String placeholder) {
        this.placeholder = placeholder;
        return this;
    }

    public TextField text(String value) {
        this.text.setLength(0);
        this.text.append(value);
        this.caret = this.text.length();
        return this;
    }

    public String getText() {
        return this.text.toString();
    }

    @Override
    public boolean isFocusable() {
        return true;
    }

    @Override
    public void renderBackground(GuiManager gui, double mx, double my) {
        Texture tex = (this.focused || this.hovered)
                ? gui.textures().textFieldHighlighted : gui.textures().textField;
        gui.sprites().drawNineSlice(tex, this.x, this.y, this.w, this.h, 3);

        /* Caret als 1-px-Rect im Sprite-Pass (zeitbasiertes Blinken, kein Tick nötig). */
        if (this.focused && (System.currentTimeMillis() / 500) % 2 == 0) {
            float cx = this.x + PAD + gui.font().getStringWidth(
                    this.text.substring(0, this.caret), TEXT_SIZE);
            gui.sprites().drawRect(cx, this.y + 4, 1, this.h - 8, 1f, 1f, 1f, 1f);
        }
    }

    @Override
    public void renderText(GuiManager gui, double mx, double my) {
        float ty = this.y + (this.h - gui.font().lineHeight(TEXT_SIZE)) / 2f;
        if (this.text.isEmpty() && !this.focused) {
            gui.font().drawString(this.placeholder, this.x + PAD, ty, TEXT_SIZE, PLACEHOLDER);
        } else {
            gui.font().drawStringWithShadow(this.text.toString(), this.x + PAD, ty, TEXT_SIZE, Colors.WHITE);
        }
    }

    @Override
    public boolean mousePressed(double mx, double my, int button) {
        /* Fokus setzt der GuiScreen (über isFocusable) — hier nur konsumieren, wenn getroffen. */
        return this.enabled && button == GLFW.GLFW_MOUSE_BUTTON_LEFT && this.isMouseOver(mx, my);
    }

    @Override
    public boolean charTyped(int codepoint) {
        if (!this.focused) return false;
        if (this.text.length() >= this.maxLength) return true;
        if (this.filter != null && !this.filter.test(codepoint)) return true;
        this.text.insert(this.caret, Character.toChars(codepoint));
        this.caret += Character.charCount(codepoint);
        return true;
    }

    @Override
    public boolean keyPressed(int key) {
        if (!this.focused) return false;
        switch (key) {
            case GLFW.GLFW_KEY_BACKSPACE -> {
                if (this.caret > 0) {
                    this.text.deleteCharAt(this.caret - 1);
                    this.caret--;
                }
            }
            case GLFW.GLFW_KEY_DELETE -> {
                if (this.caret < this.text.length()) this.text.deleteCharAt(this.caret);
            }
            case GLFW.GLFW_KEY_LEFT -> this.caret = Math.max(0, this.caret - 1);
            case GLFW.GLFW_KEY_RIGHT -> this.caret = Math.min(this.text.length(), this.caret + 1);
            case GLFW.GLFW_KEY_HOME -> this.caret = 0;
            case GLFW.GLFW_KEY_END -> this.caret = this.text.length();
            case GLFW.GLFW_KEY_ESCAPE -> {
                return false; // ESC bewusst durchreichen (GuiScreen: zurück)
            }
            default -> {
                /* Alle übrigen Tasten schlucken, solange fokussiert — sonst wirken
                   Buchstaben gleichzeitig als Hotkeys/Schließ-Tasten. */
            }
        }
        return true;
    }
}
