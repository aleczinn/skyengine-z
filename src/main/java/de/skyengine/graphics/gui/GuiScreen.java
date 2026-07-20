package de.skyengine.graphics.gui;

import de.skyengine.core.SkyEngine;
import de.skyengine.graphics.gui.widget.GuiComponent;
import de.skyengine.graphics.texture.Texture;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/**
 * Basis eines GUI-Bildschirms (Menü, Truhe, Inventar, ...). Der {@link GuiManager} hält höchstens
 * einen offenen GuiScreen, zeigt dann den Cursor und routet alle Eingaben hierher.
 *
 * <p>Koordinaten kommen im <b>virtuellen</b> GUI-Raum (bereits GUI-skaliert). Widgets werden in
 * {@link #init} gebaut und layoutet — der GuiManager ruft init beim Öffnen und bei jeder Änderung
 * von Fenstergröße/GUI-Scale erneut auf (kein Layout pro Frame).
 *
 * <p>Navigation über {@code parent}: „Zurück"/ESC öffnet den Eltern-GuiScreen wieder
 * ({@link #goBack}); ohne Eltern schließt der GuiScreen (sofern {@link #isClosable()}).
 */
public abstract class GuiScreen {

    protected final List<GuiComponent> components = new ArrayList<>();
    protected final GuiScreen parent;

    protected GuiScreen(GuiScreen parent) {
        this.parent = parent;
    }

    /** Widgets (neu) bauen und layouten. Wird bei Größen-/Scale-Änderung erneut aufgerufen. */
    public void init(GuiManager gui, float vW, float vH) {}

    /**
     * Default-Render: Hintergrund + alle Widgets in zwei Pässen (Sprites, dann Font).
     * Screens mit eigener Zeichnung (z.B. Slot-GUIs) überschreiben diese Methode.
     */
    public void render(GuiManager gui, double mouseX, double mouseY) {
        SpriteRenderer sr = gui.sprites();
        sr.begin(gui.vWidth(), gui.vHeight());
        this.renderBackground(gui);
        for (GuiComponent c : this.components) {
            if (!c.visible) continue;
            c.updateHover(mouseX, mouseY);
            c.renderBackground(gui, mouseX, mouseY);
        }
        sr.end();

        gui.font().begin(gui.vWidth(), gui.vHeight());
        for (GuiComponent c : this.components) {
            if (c.visible) c.renderText(gui, mouseX, mouseY);
        }
        gui.font().end();
    }

    /**
     * Hintergrund im Sprite-Pass: über einer laufenden Welt wird gedimmt; ohne Welt
     * (Hauptmenü-Kontext) kommt das Hintergrundbild (object-cover) — für Untermenü-Lesbarkeit
     * leicht abgedunkelt — bzw. der Kachel-Fallback, wenn kein Bild vorliegt.
     * Das GuiMainMenu überschreibt das und zeigt das Bild ungedimmt.
     */
    protected void renderBackground(GuiManager gui) {
        float vW = gui.vWidth(), vH = gui.vHeight();
        if (SkyEngine.get().getGame().getWorld() != null) {
            gui.sprites().drawRect(0, 0, vW, vH, 0f, 0f, 0f, 0.4f);
            return;
        }
        if (this.drawMenuImage(gui)) {
            gui.sprites().drawRect(0, 0, vW, vH, 0f, 0f, 0f, 0.4f);
        } else {
            this.drawMenuTiles(gui);
        }
    }

    /**
     * Zeichnet das Menü-Hintergrundbild als <b>object-cover</b> (CSS-Analogon): gleichmäßig
     * skaliert auf die kleinste Größe, die den Viewport voll bedeckt, zentriert — der Überstand
     * wird beidseitig abgeschnitten (Ultrawide: links/rechts crop, 16:9 exakt, nie verzerrt).
     * @return false, wenn kein Bild geladen ist (Fallback: {@link #drawMenuTiles}).
     */
    protected boolean drawMenuImage(GuiManager gui) {
        Texture image = gui.textures().menuBackgroundImage;
        if (image == null) return false;
        float vW = gui.vWidth(), vH = gui.vHeight();
        float scale = Math.max(vW / image.getWidth(), vH / image.getHeight());
        float w = image.getWidth() * scale, h = image.getHeight() * scale;
        gui.sprites().drawSprite(image, (vW - w) / 2f, (vH - h) / 2f, w, h);
        return true;
    }

    /** Kachel-Fallback: halbtransparente MC-Menü-Kachel über Schwarz. */
    protected void drawMenuTiles(GuiManager gui) {
        float vW = gui.vWidth(), vH = gui.vHeight();
        gui.sprites().drawRect(0, 0, vW, vH, 0.06f, 0.06f, 0.06f, 1f);
        Texture tile = gui.textures().menuBackground;
        for (float y = 0; y < vH; y += MENU_TILE) {
            for (float x = 0; x < vW; x += MENU_TILE) {
                gui.sprites().drawSprite(tile, x, y, MENU_TILE, MENU_TILE);
            }
        }
    }

    protected static final float MENU_TILE = 32;

    /** Maus-Druck: Fokus-Wechsel + Weitergabe an die Widgets. true = konsumiert. */
    public boolean mousePressed(GuiManager gui, double mouseX, double mouseY, int button) {
        GuiComponent clicked = null;
        for (GuiComponent c : this.components) {
            if (c.visible && c.mousePressed(mouseX, mouseY, button)) {
                clicked = c;
                break;
            }
        }
        /* Fokus: angeklicktes fokussierbares Widget erhält ihn, alle anderen verlieren ihn. */
        for (GuiComponent c : this.components) {
            c.setFocused(c == clicked && c.isFocusable());
        }
        return clicked != null;
    }

    public void mouseReleased(GuiManager gui, double mouseX, double mouseY, int button) {
        for (GuiComponent c : this.components) {
            if (c.visible) c.mouseReleased(mouseX, mouseY, button);
        }
    }

    public void mouseDragged(GuiManager gui, double mouseX, double mouseY, int button) {
        for (GuiComponent c : this.components) {
            if (c.visible) c.mouseDragged(mouseX, mouseY, button);
        }
    }

    public boolean mouseScrolled(GuiManager gui, double mouseX, double mouseY, double amount) {
        for (GuiComponent c : this.components) {
            if (c.visible && c.mouseScrolled(mouseX, mouseY, amount)) return true;
        }
        return false;
    }

    /** Taste gedrückt: erst das fokussierte Widget, dann Default-ESC (zurück/schließen). */
    public boolean keyPressed(GuiManager gui, int key) {
        GuiComponent focused = this.focusedComponent();
        if (focused != null && focused.keyPressed(key)) return true;
        if (key == GLFW.GLFW_KEY_ESCAPE) {
            this.goBack(gui);
            return true;
        }
        return false;
    }

    /** Text-Eingabe (Unicode-Codepoint) an das fokussierte Widget. */
    public boolean charTyped(GuiManager gui, int codepoint) {
        GuiComponent focused = this.focusedComponent();
        return focused != null && focused.charTyped(codepoint);
    }

    protected GuiComponent focusedComponent() {
        for (GuiComponent c : this.components) {
            if (c.isFocused()) return c;
        }
        return null;
    }

    /** Zurück zum Eltern-GuiScreen bzw. schließen, wenn es keinen gibt. */
    protected void goBack(GuiManager gui) {
        if (this.parent != null) {
            gui.open(this.parent);
        } else if (this.isClosable()) {
            gui.close();
        }
    }

    /** true: die Welt tickt nicht, solange dieser GuiScreen offen ist (Pause-Menü). */
    public boolean pausesGame() {
        return false;
    }

    /**
     * true: der GuiScreen beansprucht gerade ALLE Tasten exklusiv (laufende Keybind-Aufnahme) —
     * auch die sonst immer aktiven Hotkeys (F2/F3/F11) müssen dann pausieren.
     */
    public boolean capturesKeys() {
        return false;
    }

    /** true: die Inventar-Taste schließt diesen GuiScreen (nur Container-Screens). */
    public boolean closesOnInventoryKey() {
        return false;
    }

    /** false: GuiScreen kann nicht geschlossen werden (Titelbildschirm, Ladebildschirm). */
    public boolean isClosable() {
        return true;
    }

    /** Aufräumen beim Schließen (getragenen Stapel zurücklegen, Truhendeckel schließen, ...). */
    public void onClose() {}
}
