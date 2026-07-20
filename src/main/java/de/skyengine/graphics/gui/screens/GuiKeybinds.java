package de.skyengine.graphics.gui.screens;

import de.skyengine.core.settings.GameSettings;
import de.skyengine.core.settings.KeyBindings;
import de.skyengine.graphics.gui.GuiManager;
import de.skyengine.graphics.gui.GuiScreen;
import de.skyengine.graphics.gui.layout.Anchor;
import de.skyengine.graphics.gui.layout.HStack;
import de.skyengine.graphics.gui.layout.VStack;
import de.skyengine.graphics.gui.widget.Button;
import de.skyengine.graphics.gui.widget.GuiComponent;
import de.skyengine.graphics.gui.widget.KeybindButton;
import de.skyengine.graphics.gui.widget.Label;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/**
 * Tastenbelegungs-Menü: scrollbare Liste (Scissor-Clipping) mit einer Zeile pro Aktion
 * [Name — Taste — Reset], unten „Alle zurücksetzen" und „Fertig". Klick auf die Taste startet
 * die Aufnahme; die nächste gedrückte Taste bindet, ESC bricht ab. Konflikte werden rot
 * markiert, aber erlaubt. Gespeichert wird beim Verlassen.
 */
public final class GuiKeybinds extends GuiScreen {

    private static final float ROW_GAP = 2;
    private static final float SCROLL_STEP = 22; // eine Zeile pro Rastung

    /* Scroll-Liste: getrennt von components (eigenes Clipping + Routing). */
    private final List<GuiComponent> rowComponents = new ArrayList<>();
    private VStack rows;
    private final List<KeybindButton> keyButtons = new ArrayList<>();

    private float listTop, listBottom, rowsX;
    private double scrollOffset;

    public GuiKeybinds(GuiScreen parent) {
        super(parent);
    }

    @Override
    public boolean pausesGame() {
        return this.parent != null && this.parent.pausesGame();
    }

    @Override
    public boolean capturesKeys() {
        return this.capturing() != null;
    }

    @Override
    public void init(GuiManager gui, float vW, float vH) {
        this.components.clear();
        this.rowComponents.clear();
        this.keyButtons.clear();

        Label title = new Label("Tastenbelegung", 14).measure(gui);
        title.layoutAt((vW - title.width()) / 2f, 6);

        Button resetAll = new Button("Alle zurücksetzen", 150, 20, () -> {
            GameSettings.get().keyBindings = KeyBindings.defaults();
            for (KeybindButton b : this.keyButtons) b.refresh();
        });
        Button done = new Button("Fertig", 150, 20, () -> this.goBack(gui));

        this.components.add(title);
        this.components.add(new HStack(6, resetAll, done).anchor(Anchor.BOTTOM_CENTER, 0, 6));

        this.listTop = 6 + 14 + 6;
        this.listBottom = vH - 32;

        /* Zeilen: [Aktions-Label 110 | Keybind-Button 70 | Reset 50] */
        this.rows = new VStack(ROW_GAP);
        for (String action : KeyBindings.orderedActions()) {
            Label name = new Label(KeyBindings.label(action), 10).measure(gui);
            name.w = 110; // feste Spaltenbreite statt Textbreite (bündige Spalten)
            KeybindButton key = new KeybindButton(action, 70, 20);
            Button reset = new Button("Reset", 50, 20, () -> {
                GameSettings.get().keyBindings.put(action, KeyBindings.defaults().get(action));
                key.refresh();
            });
            this.keyButtons.add(key);
            this.rowComponents.add(name);
            this.rowComponents.add(key);
            this.rowComponents.add(reset);
            this.rows.add(new HStack(4).add(name).add(key).add(reset));
        }
        this.rowsX = (vW - this.rows.width()) / 2f;
        this.applyScroll();
    }

    private double maxScroll() {
        return Math.max(0, this.rows.height() - (this.listBottom - this.listTop));
    }

    private void applyScroll() {
        this.scrollOffset = Math.clamp(this.scrollOffset, 0, this.maxScroll());
        this.rows.layoutAt(this.rowsX, (float) (this.listTop - this.scrollOffset));
    }

    private boolean inViewport(double my) {
        return my >= this.listTop && my < this.listBottom;
    }

    private KeybindButton capturing() {
        for (KeybindButton b : this.keyButtons) {
            if (b.isCapturing()) return b;
        }
        return null;
    }

    @Override
    public void render(GuiManager gui, double mouseX, double mouseY) {
        float vW = gui.vWidth(), vH = gui.vHeight();

        gui.sprites().begin(vW, vH);
        this.renderBackground(gui);
        for (GuiComponent c : this.leaves) {
            c.updateHover(mouseX, mouseY);
            c.renderBackground(gui, mouseX, mouseY);
        }
        gui.enableScissor(0, this.listTop, vW, this.listBottom - this.listTop);
        for (GuiComponent c : this.rowComponents) {
            c.updateHover(this.inViewport(mouseY) ? mouseX : -1, mouseY);
            c.renderBackground(gui, mouseX, mouseY);
        }
        gui.disableScissor();
        gui.sprites().end();

        /* Fester Text (Titel/Footer) — eigener Pass, ungeclippt. */
        gui.font().begin(vW, vH);
        for (GuiComponent c : this.components) c.renderText(gui, mouseX, mouseY);
        gui.font().end();

        /* Listen-Text: FontRenderer flusht erst bei end() -> eigenes begin/end im Scissor. */
        gui.enableScissor(0, this.listTop, vW, this.listBottom - this.listTop);
        gui.font().begin(vW, vH);
        for (GuiComponent c : this.rowComponents) c.renderText(gui, mouseX, mouseY);
        gui.font().end();
        gui.disableScissor();
    }

    @Override
    public boolean mousePressed(GuiManager gui, double mouseX, double mouseY, int button) {
        /* Laufende Aufnahme: Klick daneben bricht ab (wie ESC). */
        KeybindButton capturing = this.capturing();
        if (capturing != null) {
            capturing.cancelCapture();
            return true;
        }
        if (super.mousePressed(gui, mouseX, mouseY, button)) return true;
        if (this.inViewport(mouseY)) {
            for (GuiComponent c : this.rowComponents) {
                if (c.mousePressed(mouseX, mouseY, button)) return true;
            }
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(GuiManager gui, double mouseX, double mouseY, double amount) {
        this.scrollOffset -= amount * SCROLL_STEP;
        this.applyScroll();
        return true;
    }

    @Override
    public boolean keyPressed(GuiManager gui, int key) {
        KeybindButton capturing = this.capturing();
        if (capturing != null) {
            if (key == GLFW.GLFW_KEY_ESCAPE) {
                capturing.cancelCapture();
            } else {
                capturing.bind(key);
            }
            return true;
        }
        return super.keyPressed(gui, key);
    }

    @Override
    public void onClose() {
        GameSettings.get().save();
    }
}
