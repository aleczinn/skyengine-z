package de.skyengine.graphics.gui.screens;

import de.skyengine.core.SkyEngine;
import de.skyengine.core.i18n.I18n;
import de.skyengine.game.world.save.WorldSaves;
import de.skyengine.game.world.save.WorldSaves.WorldSave;
import de.skyengine.graphics.color.Color4;
import de.skyengine.graphics.color.Colors;
import de.skyengine.graphics.gui.GuiManager;
import de.skyengine.graphics.gui.GuiScreen;
import de.skyengine.graphics.gui.ScrollBar;
import de.skyengine.graphics.gui.layout.Anchor;
import de.skyengine.graphics.gui.layout.HStack;
import de.skyengine.graphics.gui.layout.VStack;
import de.skyengine.graphics.gui.widget.Button;
import de.skyengine.graphics.gui.widget.GuiComponent;
import de.skyengine.graphics.gui.widget.IconButton;
import de.skyengine.graphics.gui.widget.Label;
import org.lwjgl.glfw.GLFW;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;

/**
 * Weltauswahl (Einzelspieler): scrollbare Savegame-Liste (Name, Seed, zuletzt gespielt),
 * darunter Spielen / Neue Welt / Löschen (mit Bestätigung) / Zurück.
 */
public final class GuiSelectWorld extends GuiScreen {

    private static final float ENTRY_W = 260, ENTRY_H = 28, ROW_GAP = 2;
    private static final float SCROLL_STEP = 30;
    /** Zeitfenster für Doppelklick-Laden (Klick auf den bereits selektierten Eintrag). */
    private static final long DOUBLE_CLICK_MS = 400;
    private static final Color4 SUBTITLE = new Color4(0.65f, 0.65f, 0.65f, 1f);

    private final List<GuiComponent> entries = new ArrayList<>();
    private VStack rows;
    private float listTop, listBottom, rowsX;
    private double scrollOffset;
    private final ScrollBar scrollBar = new ScrollBar();

    private List<WorldSave> saves = List.of();
    private int selected = -1;
    private long lastClickTime;
    private Button play, delete;

    public GuiSelectWorld(GuiScreen parent) {
        super(parent);
    }

    /** Ein Listeneintrag: Panel mit Name + Untertitel, Klick wählt aus. */
    private final class Entry extends GuiComponent {
        private final int index;
        private final String name;
        private final String subtitle;

        Entry(int index, WorldSave save) {
            this.index = index;
            this.name = save.level().name;
            this.subtitle = I18n.tr("world.select.seed", String.valueOf(save.level().seed)) + "  |  "
                    + new SimpleDateFormat("dd.MM.yyyy HH:mm").format(save.level().lastPlayed);
            this.w = ENTRY_W;
            this.h = ENTRY_H;
        }

        @Override
        public void renderBackground(GuiManager gui, double mx, double my) {
            gui.sprites().drawRect(this.x, this.y, this.w, this.h, 0f, 0f, 0f, 0.5f);
            if (GuiSelectWorld.this.selected == this.index) {
                /* Auswahl-Rahmen (1 px, weiß) */
                gui.sprites().drawRect(this.x, this.y, this.w, 1, 1f, 1f, 1f, 1f);
                gui.sprites().drawRect(this.x, this.y + this.h - 1, this.w, 1, 1f, 1f, 1f, 1f);
                gui.sprites().drawRect(this.x, this.y, 1, this.h, 1f, 1f, 1f, 1f);
                gui.sprites().drawRect(this.x + this.w - 1, this.y, 1, this.h, 1f, 1f, 1f, 1f);
            } else if (this.hovered) {
                gui.sprites().drawRect(this.x, this.y, this.w, this.h, 1f, 1f, 1f, 0.08f);
            }
        }

        @Override
        public void renderText(GuiManager gui, double mx, double my) {
            gui.font().drawStringWithShadow(this.name, this.x + 4, this.y + 3, 10, Colors.WHITE);
            gui.font().drawString(this.subtitle, this.x + 4, this.y + 15, 8, SUBTITLE);
        }

        @Override
        public boolean mousePressed(double mx, double my, int button) {
            if (button != GLFW.GLFW_MOUSE_BUTTON_LEFT || !this.isMouseOver(mx, my)) return false;
            /* Doppelklick auf den bereits selektierten Eintrag lädt die Welt direkt (wie MC). */
            long now = System.currentTimeMillis();
            if (GuiSelectWorld.this.selected == this.index
                    && now - GuiSelectWorld.this.lastClickTime <= DOUBLE_CLICK_MS) {
                SkyEngine.get().getGame().enterWorld(GuiSelectWorld.this.saves.get(this.index));
                return true;
            }
            GuiSelectWorld.this.lastClickTime = now;
            GuiSelectWorld.this.select(this.index);
            return true;
        }
    }

    private void select(int index) {
        this.selected = index;
        boolean has = index >= 0;
        this.play.enabled = has;
        this.delete.enabled = has;
        /* Ohne Auswahl erklären, warum die Buttons nichts tun (Tooltips gelten auch bei disabled). */
        String hint = has ? null : I18n.tr("world.select.needs_selection");
        this.play.tooltip(hint);
        this.delete.tooltip(hint);
    }

    @Override
    public void init(GuiManager gui, float vW, float vH) {
        this.components.clear();
        this.entries.clear();
        this.saves = WorldSaves.list();
        if (this.selected >= this.saves.size()) this.selected = -1;

        Label title = new Label(I18n.tr("world.select.title"), 14).measure(gui);
        title.layoutAt((vW - title.width()) / 2f, 6);

        this.play = new Button(I18n.tr("world.select.play"), 130, 20, () -> {
            if (this.selected >= 0) {
                SkyEngine.get().getGame().enterWorld(this.saves.get(this.selected));
            }
        });
        Button create = new Button(I18n.tr("world.select.create"), 130, 20, () -> gui.open(new GuiCreateWorld(this)));
        this.delete = new Button(I18n.tr("world.select.delete"), 130, 20, () -> {
            if (this.selected < 0) return;
            WorldSave save = this.saves.get(this.selected);
            gui.open(new GuiConfirm(this, I18n.tr("world.select.delete_title"),
                    I18n.tr("world.select.delete_message", save.level().name), () -> {
                WorldSaves.delete(save);
                this.selected = -1;
            }));
        });
        Button back = new Button(I18n.tr("gui.back"), 130, 20, () -> this.goBack(gui));

        this.components.add(title);
        /* Import oben rechts (eigene Seite) — kollidiert weder mit dem Titel noch mit der Liste. */
        this.components.add(new IconButton(gui.textures().iconImport, null, 20,
                () -> gui.open(new GuiImportWorld(this)))
                .uv(12, 2, 16, 16) // Globus-Kern aus dem 40×20-Sprite
                .tooltip(I18n.tr("world.import.button") + "<br>" + I18n.tr("world.import.button_hint"))
                .anchor(Anchor.TOP_RIGHT, 6, 6));
        this.components.add(new VStack(4,
                new HStack(4, this.play, create),
                new HStack(4, this.delete, back)
        ).anchor(Anchor.BOTTOM_CENTER, 0, 6));

        this.select(this.selected);

        this.listTop = 6 + 14 + 6;
        this.listBottom = vH - 56;

        this.rows = new VStack(ROW_GAP);
        for (int i = 0; i < this.saves.size(); i++) {
            Entry entry = new Entry(i, this.saves.get(i));
            this.entries.add(entry);
            this.rows.add(entry);
        }
        if (this.saves.isEmpty()) {
            Label empty = new Label(I18n.tr("world.select.empty"), 10, SUBTITLE, false).measure(gui);
            this.entries.add(empty);
            this.rows.add(empty);
        }
        this.rowsX = (vW - ENTRY_W) / 2f;
        this.scrollBar.layout(this.rowsX + ENTRY_W + 4, this.listTop, this.listBottom - this.listTop);
        this.applyScroll();
    }

    private void applyScroll() {
        double max = Math.max(0, this.rows.height() - (this.listBottom - this.listTop));
        this.scrollOffset = Math.clamp(this.scrollOffset, 0, max);
        this.rows.layoutAt(this.rowsX, (float) (this.listTop - this.scrollOffset));
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
            c.updateHover(mouseX, mouseY);
            c.renderBackground(gui, mouseX, mouseY);
        }
        gui.enableScissor(0, this.listTop, vW, this.listBottom - this.listTop);
        for (GuiComponent c : this.entries) {
            c.updateHover(this.inViewport(mouseY) ? mouseX : -1, mouseY);
            c.renderBackground(gui, mouseX, mouseY);
        }
        gui.disableScissor();
        this.scrollBar.draw(gui, this.rows.height(), this.scrollOffset);
        gui.sprites().end();

        gui.font().begin(vW, vH);
        for (GuiComponent c : this.leaves) c.renderText(gui, mouseX, mouseY);
        gui.font().end();

        /* Listen-Text separat clippen (FontRenderer flusht erst bei end()). */
        gui.enableScissor(0, this.listTop, vW, this.listBottom - this.listTop);
        gui.font().begin(vW, vH);
        for (GuiComponent c : this.entries) c.renderText(gui, mouseX, mouseY);
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
        if (this.inViewport(mouseY)) {
            for (GuiComponent c : this.entries) {
                if (c.mousePressed(mouseX, mouseY, button)) return true;
            }
        }
        return false;
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
    }

    @Override
    public void mouseReleased(GuiManager gui, double mouseX, double mouseY, int button) {
        this.scrollBar.mouseReleased();
        super.mouseReleased(gui, mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(GuiManager gui, double mouseX, double mouseY, double amount) {
        this.scrollOffset -= amount * SCROLL_STEP;
        this.applyScroll();
        return true;
    }
}
