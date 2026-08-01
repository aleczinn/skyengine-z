package de.skyengine.graphics.gui.screens;

import de.skyengine.core.i18n.I18n;
import de.skyengine.game.world.save.WorldSaves;
import de.skyengine.graphics.color.Color4;
import de.skyengine.graphics.color.Colors;
import de.skyengine.graphics.gui.GuiManager;
import de.skyengine.graphics.gui.GuiText;
import de.skyengine.graphics.gui.GuiScreen;
import de.skyengine.graphics.gui.ScrollBar;
import de.skyengine.graphics.gui.text.RichText;
import de.skyengine.graphics.gui.layout.Anchor;
import de.skyengine.graphics.gui.layout.HStack;
import de.skyengine.graphics.gui.layout.VStack;
import de.skyengine.graphics.gui.widget.Button;
import de.skyengine.graphics.gui.widget.GuiComponent;
import de.skyengine.graphics.gui.widget.Label;
import de.skyengine.graphics.gui.widget.TextField;
import de.skyengine.mcimport.McSaves;
import de.skyengine.mcimport.McWorldImporter;
import org.lwjgl.glfw.GLFW;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Minecraft-Welt importieren (aus der Weltauswahl): links die gefundenen Welten aus
 * {@code %APPDATA%\.minecraft\saves}, rechts Quell-Pfad (frei editierbar) + Ziel-Weltname,
 * darunter Fortschrittsbalken und der Live-Log des Importers.
 *
 * <p>Der Import selbst läuft auf einem eigenen Daemon-Thread ({@link McWorldImporter#run}) —
 * er macht nur Datei-IO in den neuen Save-Ordner, während keine Welt geladen ist. Log-Zeilen
 * kommen über eine {@link ConcurrentLinkedQueue} und werden im Render-Thread eingesammelt.
 * {@code WorldSaves.create} passiert bewusst VOR dem Thread-Start (Render-Thread-only).
 *
 * <p>Es gibt keinen Abbruch: Ein Spiel-Exit mitten im Import beendet den Daemon-Thread hart
 * und lässt eine unvollständige Welt zurück (die man in der Weltauswahl löschen kann).
 */
public final class GuiImportWorld extends GuiScreen {

    private static final float ENTRY_H = 24, ROW_GAP = 2;
    private static final float SCROLL_STEP = 30;
    private static final float LOG_SIZE = GuiText.SMALL;
    private static final Color4 SUBTITLE = new Color4(0.65f, 0.65f, 0.65f, 1f);

    /* Layout (virtueller Raum, min. 340×210). */
    private static final float MARGIN = 8, TOP = 22, UPPER_H = 76, BAR_H = 4;
    private static final float LIST_W = 150;

    private final List<GuiComponent> entries = new ArrayList<>();
    private VStack rows;
    private float listTop, listBottom, rowsX, listW;
    private double scrollOffset;
    private final ScrollBar scrollBar = new ScrollBar();

    private List<McSaves.McSave> saves = List.of();
    private int selected = -1;

    private TextField source, name;
    /** Ein Button für beides: startet den Import bzw. bricht den laufenden ab. */
    private Button action, back;
    /** Zuletzt gezeichneter Zustand — Beschriftung/Tooltip nur bei Wechsel neu setzen. */
    private Boolean lastRunning;
    private LogView log;

    /* Import-Zustand (überlebt init(), das bei jedem Resize/Scale-Wechsel neu läuft). */
    private final List<RichText> lines = new ArrayList<>();
    private final ConcurrentLinkedQueue<String> pending = new ConcurrentLinkedQueue<>();
    private volatile boolean running;
    private volatile boolean cancelRequested;
    private volatile int stepDone, stepTotal;
    /** Vom Worker gesetzt: die angelegte Welt soll verworfen werden (Abbruch) — löscht render(). */
    private volatile WorldSaves.WorldSave discardWorld;
    private String sourcePath = "";
    private String worldName = "";
    /** Zuletzt automatisch eingesetzter Name — nur der darf beim Wechsel überschrieben werden. */
    private String autoName = "";

    public GuiImportWorld(GuiScreen parent) {
        super(parent);
    }

    /** Ein Listeneintrag: MC-Weltname + Ordner/Datum. */
    private final class Entry extends GuiComponent {
        private final int index;
        private final String name;
        private final String subtitle;

        Entry(int index, McSaves.McSave save) {
            this.index = index;
            this.name = save.name();
            this.subtitle = save.dir().getName() + "  |  "
                    + new SimpleDateFormat("dd.MM.yyyy").format(save.lastPlayed());
            this.w = GuiImportWorld.this.listW - 8;
            this.h = ENTRY_H;
        }

        @Override
        public void renderBackground(GuiManager gui, double mx, double my) {
            gui.sprites().drawRect(this.x, this.y, this.w, this.h, 0f, 0f, 0f, 0.5f);
            if (GuiImportWorld.this.selected == this.index) {
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
            gui.font().drawStringWithShadow(this.name, this.x + 4, this.y + 2, GuiText.COMPACT, Colors.WHITE);
            gui.font().drawString(this.subtitle, this.x + 4, this.y + 13, GuiText.TINY, SUBTITLE);
        }

        @Override
        public boolean mousePressed(double mx, double my, int button) {
            if (button != GLFW.GLFW_MOUSE_BUTTON_LEFT || !this.isMouseOver(mx, my)) return false;
            GuiImportWorld.this.select(this.index);
            return true;
        }
    }

    /** Scrollbarer Text-Block für die Importer-Ausgabe (folgt dem Ende, bis man hochscrollt). */
    private final class LogView extends GuiComponent {
        private double scroll;
        private float lastMax;
        private boolean follow = true;

        @Override
        public void renderBackground(GuiManager gui, double mx, double my) {
            gui.sprites().drawRect(this.x, this.y, this.w, this.h, 0f, 0f, 0f, 0.6f);
        }

        @Override
        public void renderText(GuiManager gui, double mx, double my) {
            List<RichText> lines = GuiImportWorld.this.lines;
            if (lines.isEmpty()) return;
            float lh = Math.max(1, gui.font().lineHeight(LOG_SIZE));
            this.lastMax = Math.max(0, lines.size() * lh - (this.h - 4));
            this.scroll = this.follow ? this.lastMax : Math.clamp(this.scroll, 0, this.lastMax);

            int first = (int) (this.scroll / lh);
            int count = (int) (this.h / lh) + 2;
            /* Eigenes begin/end im Scissor — der FontRenderer flusht erst bei end(). */
            gui.font().end();
            gui.enableScissor(this.x, this.y, this.w, this.h);
            gui.font().begin(gui.vWidth(), gui.vHeight());
            for (int i = first; i < Math.min(lines.size(), first + count); i++) {
                gui.font().drawRich(lines.get(i), this.x + 3,
                        this.y + 2 + i * lh - (float) this.scroll, LOG_SIZE, Colors.WHITE, false);
            }
            gui.font().end();
            gui.disableScissor();
            gui.font().begin(gui.vWidth(), gui.vHeight());
        }

        @Override
        public boolean mouseScrolled(double mx, double my, double amount) {
            if (!this.isMouseOver(mx, my)) return false;
            this.scroll = Math.clamp(this.scroll - amount * 20, 0, this.lastMax);
            this.follow = this.scroll >= this.lastMax - 0.5;
            return true;
        }
    }

    private void select(int index) {
        this.selected = index;
        if (index < 0 || index >= this.saves.size()) return;
        McSaves.McSave save = this.saves.get(index);
        this.source.text(save.dir().getAbsolutePath());
        /* Namen nur vorbelegen, solange der Nutzer ihn nicht selbst angepasst hat. */
        if (this.name.getText().isBlank() || this.name.getText().equals(this.autoName)) {
            this.autoName = save.name();
            this.name.text(this.autoName);
        }
    }

    @Override
    public void init(GuiManager gui, float vW, float vH) {
        /* Eingaben über Resize/Scale-Wechsel retten (init baut die Widgets neu). */
        if (this.source != null) this.sourcePath = this.source.getText();
        if (this.name != null) this.worldName = this.name.getText();
        this.components.clear();
        this.entries.clear();

        Label title = new Label(I18n.tr("world.import.title"), GuiText.MEDIUM).measure(gui);
        title.layoutAt((vW - title.width()) / 2f, 6);
        this.components.add(title);

        /* --- Rechte Spalte: Quell-Pfad + Ziel-Name --- */
        this.listW = LIST_W;
        float fieldsX = MARGIN + this.listW + MARGIN;
        float fieldW = Math.max(120, vW - fieldsX - MARGIN);
        this.source = new TextField(fieldW, 18, 256, null).text(this.sourcePath);
        this.name = new TextField(fieldW, 18, 32, null).text(this.worldName);
        VStack fields = new VStack(2,
                new Label(I18n.tr("world.import.source"), GuiText.SMALL).measure(gui),
                this.source,
                new Label(I18n.tr("world.import.name"), GuiText.SMALL).measure(gui),
                this.name).align(VStack.Align.LEFT);
        fields.layoutAt(fieldsX, TOP);
        this.components.add(fields);

        /* --- Log-Block --- */
        float logTop = TOP + UPPER_H + BAR_H + 6;
        float logBottom = vH - 28;
        this.log = new LogView();
        this.log.w = vW - 2 * MARGIN;
        this.log.h = Math.max(20, logBottom - logTop);
        this.log.layoutAt(MARGIN, logTop);
        this.components.add(this.log);

        /* --- Footer: EIN Button für Start/Abbruch (spart das Ausgrauen), plus Zurück. --- */
        this.action = new Button("", 120, 20, () -> {
            if (this.running) {
                this.cancelRequested = true;
                this.addLine("<gold>" + I18n.tr("world.import.cancelling") + "</>");
            } else {
                this.startImport();
            }
        });
        this.lastRunning = null; // erzwingt das Setzen von Beschriftung/Tooltip im ersten render
        this.back = new Button(I18n.tr("gui.back"), 100, 20, () -> this.goBack(gui));
        this.components.add(new HStack(4, this.action, this.back)
                .anchor(Anchor.BOTTOM_CENTER, 0, 4));

        /* --- Linke Spalte: MC-Welten --- */
        this.saves = McSaves.list(McSaves.defaultSavesDir());
        if (this.selected >= this.saves.size()) this.selected = -1;
        this.listTop = TOP;
        this.listBottom = TOP + UPPER_H;
        this.rows = new VStack(ROW_GAP).align(VStack.Align.LEFT);
        for (int i = 0; i < this.saves.size(); i++) {
            Entry entry = new Entry(i, this.saves.get(i));
            this.entries.add(entry);
            this.rows.add(entry);
        }
        if (this.saves.isEmpty()) {
            Label empty = new Label(I18n.tr("world.import.empty"), GuiText.TINY, SUBTITLE, false).measure(gui);
            this.entries.add(empty);
            this.rows.add(empty);
        }
        this.rowsX = MARGIN;
        this.scrollBar.layout(MARGIN + this.listW - ScrollBar.WIDTH, this.listTop,
                this.listBottom - this.listTop);
        this.applyScroll();
    }

    private void applyScroll() {
        double max = Math.max(0, this.rows.height() - (this.listBottom - this.listTop));
        this.scrollOffset = Math.clamp(this.scrollOffset, 0, max);
        this.rows.layoutAt(this.rowsX, (float) (this.listTop - this.scrollOffset));
    }

    private boolean inViewport(double mx, double my) {
        return my >= this.listTop && my < this.listBottom && mx < MARGIN + this.listW;
    }

    /* --- Import --- */

    private void addLine(String line) {
        this.pending.add(line);
    }

    private void startImport() {
        if (this.running) return;
        String path = this.source.getText().trim();
        String worldName = this.name.getText().trim();
        if (path.isEmpty() || !new File(path).isDirectory()) {
            this.addLine("<red>" + I18n.tr("world.import.error_source") + "</>");
            return;
        }
        if (worldName.isEmpty()) {
            this.addLine("<red>" + I18n.tr("world.import.error_name") + "</>");
            return;
        }

        /* Ziel-Welt auf dem Render-Thread anlegen (WorldSaves ist render-thread-only). */
        WorldSaves.WorldSave save = McWorldImporter.createTargetWorld(worldName);
        File mcWorld = new File(path);
        this.running = true;
        this.cancelRequested = false;
        this.stepDone = 0;
        this.stepTotal = 0;
        this.addLine("<b>Import:</> " + mcWorld.getAbsolutePath() + " -> " + save.dirName());

        McWorldImporter.Progress progress = new McWorldImporter.Progress() {
            @Override
            public void log(String zeile) {
                GuiImportWorld.this.addLine(zeile);
            }

            @Override
            public void step(int fertig, int gesamt) {
                GuiImportWorld.this.stepDone = fertig;
                GuiImportWorld.this.stepTotal = gesamt;
            }

            @Override
            public boolean cancelled() {
                return GuiImportWorld.this.cancelRequested;
            }
        };

        Thread thread = new Thread(() -> {
            try {
                McWorldImporter.Result result = McWorldImporter.run(mcWorld, save, progress);
                if (result.cancelled()) {
                    /* Halbe Welt ist wertlos — löschen lässt der Render-Thread (WorldSaves!). */
                    GuiImportWorld.this.discardWorld = save;
                    progress.log("<red>" + I18n.tr("world.import.cancelled") + "</>");
                    return;
                }
                progress.log("");
                progress.log("<green>=== Import fertig: " + save.dirName() + " ===</>");
                progress.log(String.format("Engine-Chunks geschrieben:  <green>%,d</> (%,d ms)",
                        result.chunksImported(), result.elapsedMs()));
                progress.log(String.format("Fehlende MC-Chunks:         %,d (Quadrant bleibt Luft)",
                        result.mcChunksMissing()));
                progress.log(String.format("Zellen außerhalb 0..511:    %,d", result.cellsOutOfRange()));
                progress.log(String.format("Truhen übernommen:          %,d (%,d Items, %,d unbekannt)",
                        result.chestsImported(), result.itemsImported(), result.itemsSkipped()));
                progress.log(String.format("Andere BlockEntities:       %,d übersprungen",
                        result.blockEntitiesSkipped()));
            } catch (Exception e) {
                progress.log("<red>FEHLER: " + e.getMessage() + "</>");
            } finally {
                GuiImportWorld.this.running = false;
            }
        }, "mc-import");
        thread.setDaemon(true);
        thread.start();
    }

    /* --- Rendering / Events --- */

    @Override
    public void render(GuiManager gui, double mouseX, double mouseY) {
        String line;
        while ((line = this.pending.poll()) != null) {
            this.lines.add(RichText.parse(line));
        }
        /* Abgebrochener Import: die halbe Welt hier löschen — WorldSaves ist render-thread-only. */
        WorldSaves.WorldSave discard = this.discardWorld;
        if (discard != null && !this.running) {
            this.discardWorld = null;
            WorldSaves.delete(discard);
        }
        /* Während des Imports keine Eingaben/Navigation (halb importierte Welt vermeiden);
           der Aktions-Button bleibt aktiv und heißt dann „Abbrechen". */
        boolean idle = !this.running;
        this.back.enabled = idle;
        this.source.enabled = idle;
        this.name.enabled = idle;
        this.action.enabled = idle || !this.cancelRequested;
        if (this.lastRunning == null || this.lastRunning != this.running) {
            this.lastRunning = this.running;
            this.action.setLabel(I18n.tr(idle ? "world.import.start" : "world.import.cancel"));
            this.action.tooltip(I18n.tr(idle ? "world.import.start_hint" : "world.import.cancel_hint"));
        }

        float vW = gui.vWidth(), vH = gui.vHeight();
        gui.sprites().begin(vW, vH);
        this.renderBackground(gui);
        for (GuiComponent c : this.leaves) {
            c.updateHover(mouseX, mouseY);
            c.renderBackground(gui, mouseX, mouseY);
        }
        gui.enableScissor(0, this.listTop, MARGIN + this.listW, this.listBottom - this.listTop);
        for (GuiComponent c : this.entries) {
            c.updateHover(this.inViewport(mouseX, mouseY) ? mouseX : -1, mouseY);
            c.renderBackground(gui, mouseX, mouseY);
        }
        gui.disableScissor();
        this.scrollBar.draw(gui, this.rows.height(), this.scrollOffset);

        /* Fortschrittsbalken (Stil wie GuiWorldLoading) über dem Log. */
        float barY = TOP + UPPER_H + 2;
        float barW = vW - 2 * MARGIN;
        float progress = this.stepTotal > 0 ? Math.min(1f, this.stepDone / (float) this.stepTotal) : 0f;
        gui.sprites().drawRect(MARGIN, barY, barW, BAR_H, 1f, 1f, 1f, 0.35f);
        gui.sprites().drawRect(MARGIN, barY, barW * progress, BAR_H, 1f, 1f, 1f, 0.9f);
        gui.sprites().end();

        gui.font().begin(vW, vH);
        for (GuiComponent c : this.leaves) c.renderText(gui, mouseX, mouseY);
        gui.font().end();

        /* Listen-Text separat clippen (FontRenderer flusht erst bei end()). */
        gui.enableScissor(0, this.listTop, MARGIN + this.listW, this.listBottom - this.listTop);
        gui.font().begin(vW, vH);
        for (GuiComponent c : this.entries) c.renderText(gui, mouseX, mouseY);
        gui.font().end();
        gui.disableScissor();
    }

    @Override
    public boolean keyPressed(GuiManager gui, int key) {
        if (this.running) return true; // auch ESC schlucken, solange importiert wird
        return super.keyPressed(gui, key);
    }

    @Override
    public boolean mousePressed(GuiManager gui, double mouseX, double mouseY, int button) {
        double barOffset = this.scrollBar.mousePressed(mouseX, mouseY, this.rows.height(), this.scrollOffset);
        if (barOffset >= 0) {
            this.scrollOffset = barOffset;
            this.applyScroll();
            return true;
        }
        /* Widgets bekommen den Klick immer — die während des Imports gesperrten sind disabled,
           nur „Abbrechen" reagiert dann noch. */
        if (super.mousePressed(gui, mouseX, mouseY, button)) return true;
        if (!this.running && this.inViewport(mouseX, mouseY)) {
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
        if (this.inViewport(mouseX, mouseY)) {
            this.scrollOffset -= amount * SCROLL_STEP;
            this.applyScroll();
            return true;
        }
        return super.mouseScrolled(gui, mouseX, mouseY, amount);
    }
}
