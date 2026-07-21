package de.skyengine.graphics.gui.screens;

import de.skyengine.graphics.gui.GuiManager;
import de.skyengine.graphics.gui.GuiScreen;
import de.skyengine.graphics.gui.ScrollBar;
import de.skyengine.graphics.gui.layout.Anchor;
import de.skyengine.graphics.gui.layout.VStack;
import de.skyengine.graphics.gui.widget.Button;
import de.skyengine.graphics.gui.widget.GuiComponent;
import de.skyengine.graphics.gui.widget.Label;

import java.util.ArrayList;
import java.util.List;

/**
 * Basis der Options-Unterseiten (MC-Layout): Titel oben, Inhalt mittig, „Fertig" IMMER unten
 * angedockt. Passt der Inhalt nicht zwischen Titel und Footer, scrollt der Mittelteil
 * (Muster GuiKeybinds): Scroll = Re-Layout des Inhalts-VStacks an verschobener Y-Position —
 * die Widgets tragen ihre gescrollten x/y dadurch selbst, Events brauchen keine Umrechnung,
 * geclippt wird nur per Scissor. Unterklassen liefern {@link #title()} und füllen
 * {@link #buildContent} (kein Titel, kein Fertig — das stellt die Basis).
 *
 * <p>Grenze: Der Inhalt läuft außerhalb der leaves-Mechanik — fokussierbare Widgets
 * (Textfelder) gehören deshalb NICHT in den Scroll-Inhalt (keyPressed/charTyped routen
 * nur über die leaves; die Options-Screens haben nur Slider/Buttons).
 */
public abstract class GuiOptionsScreen extends GuiScreen {

    private static final float SCROLL_STEP = 22; // etwa eine Zeile pro Rastung

    /* Scroll-Inhalt: getrennt von components (eigenes Clipping + Routing, wie GuiKeybinds). */
    private final List<GuiComponent> rowComponents = new ArrayList<>();
    private VStack rows;

    private float listTop, listBottom, rowsX, vQuarter;
    private double scrollOffset;
    private final ScrollBar scrollBar = new ScrollBar();

    protected GuiOptionsScreen(GuiScreen parent) {
        super(parent);
    }

    @Override
    public boolean doesPausesGame() {
        return this.parent != null && this.parent.doesPausesGame();
    }

    @Override
    public boolean blursBackground() {
        return this.parent != null && this.parent.blursBackground();
    }

    /** Titel-Text des Screens. */
    protected abstract String title();

    /** Füllt den scrollbaren Mittelteil — nur {@code content.add(...)}-Zeilen. */
    protected abstract void buildContent(GuiManager gui, VStack content);

    @Override
    public final void init(GuiManager gui, float vW, float vH) {
        this.components.clear();
        this.rowComponents.clear();

        Label title = new Label(this.title(), 14).measure(gui);
        this.components.add(title.anchor(Anchor.TOP_CENTER, 0, titleTop(vH)));
        this.components.add(new Button("Fertig", () -> this.goBack(gui)).anchor(Anchor.BOTTOM_CENTER, 0, 4));

        this.rows = new VStack(4);
        this.buildContent(gui, this.rows);
        this.rows.collectLeaves(this.rowComponents);

        /* Zonen knapp bemessen: bei der Mindest-vHöhe 210 (720p-Fenster) müssen 140 vpx
           Inhalt (Sound-/Grafik-Screen) noch OHNE Scrollbalken passen. */
        this.listTop = titleTop(vH) + 18;
        this.listBottom = vH - 26;
        this.vQuarter = vH / 4f;
        this.rowsX = (vW - this.rows.width()) / 2f;
        this.scrollBar.layout(this.rowsX + this.rows.width() + 4, this.listTop,
                this.listBottom - this.listTop);
        this.applyScroll();
    }

    private double maxScroll() {
        return Math.max(0, this.rows.height() - (this.listBottom - this.listTop));
    }

    private void applyScroll() {
        this.scrollOffset = Math.clamp(this.scrollOffset, 0, this.maxScroll());
        if (this.maxScroll() == 0) {
            /* Passt komplett: im oberen Drittel andocken (bisheriger MC-Look). */
            float y = Math.max(this.listTop, Math.min(this.vQuarter, this.listBottom - this.rows.height()));
            this.rows.layoutAt(this.rowsX, y);
        } else {
            this.rows.layoutAt(this.rowsX, (float) (this.listTop - this.scrollOffset));
        }
    }

    private boolean inViewport(double my) {
        return my >= this.listTop && my < this.listBottom;
    }

    @Override
    public void render(GuiManager gui, double mouseX, double mouseY) {
        float vW = gui.vWidth(), vH = gui.vHeight();

        gui.sprites().begin(vW, vH);
        this.renderBackground(gui);
        for (GuiComponent c : this.leaves) {
            if (!c.visible) continue;
            c.updateHover(mouseX, mouseY);
            c.renderBackground(gui, mouseX, mouseY);
        }
        gui.enableScissor(0, this.listTop, vW, this.listBottom - this.listTop);
        for (GuiComponent c : this.rowComponents) {
            if (!c.visible) continue;
            /* Außerhalb des Viewports kein Hover — sonst leuchten halb rausgescrollte Widgets. */
            c.updateHover(this.inViewport(mouseY) ? mouseX : -1, mouseY);
            c.renderBackground(gui, mouseX, mouseY);
        }
        gui.disableScissor();
        this.scrollBar.draw(gui, this.rows.height(), this.scrollOffset);
        gui.sprites().end();

        /* Fester Text (Titel/Fertig) — eigener Pass, ungeclippt. */
        gui.font().begin(vW, vH);
        for (GuiComponent c : this.leaves) {
            if (c.visible) c.renderText(gui, mouseX, mouseY);
        }
        gui.font().end();

        /* Inhalts-Text: FontRenderer flusht erst bei end() -> eigenes begin/end im Scissor. */
        gui.enableScissor(0, this.listTop, vW, this.listBottom - this.listTop);
        gui.font().begin(vW, vH);
        for (GuiComponent c : this.rowComponents) {
            if (c.visible) c.renderText(gui, mouseX, mouseY);
        }
        gui.font().end();
        gui.disableScissor();
    }

    @Override
    public boolean mousePressed(GuiManager gui, double mouseX, double mouseY, int button) {
        double barOffset = this.scrollBar.mousePressed(mouseX, mouseY, this.rows.height(), this.scrollOffset);
        if (barOffset >= 0) {
            this.scrollOffset = barOffset;
            this.applyScroll();
            return true;
        }
        if (super.mousePressed(gui, mouseX, mouseY, button)) return true;
        if (!this.inViewport(mouseY)) return false;

        /* Wie GuiScreen.mousePressed, nur über den Scroll-Inhalt (inkl. Klick-Sound). */
        GuiComponent clicked = null;
        for (GuiComponent c : this.rowComponents) {
            if (c.visible && c.mousePressed(mouseX, mouseY, button)) {
                clicked = c;
                break;
            }
        }
        for (GuiComponent c : this.rowComponents) {
            c.setFocused(c == clicked && c.isFocusable());
        }
        if (clicked instanceof Button) {
            gui.sound().playUiClick();
        }
        return clicked != null;
    }

    @Override
    public void mouseDragged(GuiManager gui, double mouseX, double mouseY, int button) {
        double barOffset = this.scrollBar.mouseDragged(mouseY, this.rows.height());
        if (barOffset >= 0) {
            this.scrollOffset = barOffset;
            this.applyScroll();
            return;
        }
        super.mouseDragged(gui, mouseX, mouseY, button);
        for (GuiComponent c : this.rowComponents) {
            c.mouseDragged(mouseX, mouseY, button);
        }
    }

    @Override
    public void mouseReleased(GuiManager gui, double mouseX, double mouseY, int button) {
        this.scrollBar.mouseReleased();
        super.mouseReleased(gui, mouseX, mouseY, button);
        for (GuiComponent c : this.rowComponents) {
            c.mouseReleased(mouseX, mouseY, button);
        }
    }

    @Override
    public boolean mouseScrolled(GuiManager gui, double mouseX, double mouseY, double amount) {
        this.scrollOffset -= amount * SCROLL_STEP;
        this.applyScroll();
        return true;
    }
}
