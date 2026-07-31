package de.skyengine.graphics.gui.screens;

import de.skyengine.core.SkyEngine;
import de.skyengine.core.i18n.I18n;
import de.skyengine.core.settings.GameSettings;
import de.skyengine.core.settings.KeyBindings;
import de.skyengine.game.world.block.entity.ItemStorage;
import de.skyengine.game.world.item.CreativeTab;
import de.skyengine.game.world.item.CreativeTabs;
import de.skyengine.game.world.item.Item;
import de.skyengine.game.world.item.ItemStack;
import de.skyengine.game.world.item.Items;
import de.skyengine.graphics.color.Color4;
import de.skyengine.graphics.color.Colors;
import de.skyengine.graphics.gui.GuiManager;
import de.skyengine.graphics.gui.GuiText;
import de.skyengine.graphics.gui.GuiTextures;
import de.skyengine.graphics.gui.Slot;
import de.skyengine.graphics.gui.SlotGroup;
import de.skyengine.graphics.gui.SpriteRenderer;
import de.skyengine.graphics.gui.text.RichText;
import de.skyengine.graphics.gui.widget.Button;
import de.skyengine.graphics.gui.widget.TextField;
import de.skyengine.graphics.player.HeldItemMeshes;
import de.skyengine.graphics.player.PlayerRenderer;
import de.skyengine.graphics.texture.Texture;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

/**
 * Creative-Inventar (Taste E im Creative-Modus) mit den MC-Sheets
 * {@code container/creative_inventory/*} (Fenster 195×136): je eine Reiter-Reihe über und unter
 * dem Fenster, dazwischen die 9×5-Item-Liste mit Scroller und die Hotbar. Die Reiter kommen aus
 * {@link CreativeTabs} — ihr Inhalt steht in den Block-/Item-JSONs unter {@code creative_tab},
 * nicht hier im Code.
 *
 * <p>Drei Reiter-Arten: normale Item-Reiter, der Such-Reiter (Textfeld über allen Items) und der
 * Survival-Reiter, der das echte Spielerinventar samt Lösch-Slot zeigt. Die beiden Sonder-Reiter
 * sind ANGEHEFTET — Suche oben rechts, Survival-Inventar unten rechts, wie in Minecraft — und
 * damit auf jeder Seite erreichbar. Die übrigen 12 Plätze nehmen die normalen Reiter auf; passen
 * die nicht auf eine Seite, erscheint über der oberen Reihe eine Blätter-Leiste.
 *
 * <p>Die Item-Liste ist ein synthetisches, unveränderliches {@link ItemStorage}: {@code get}
 * liefert für jeden Zugriff einen frischen Einzel-Stapel, {@code set}/{@code insert}/{@code extract}
 * tun nichts. Dadurch laufen alle Mutationen der geerbten Klick-Logik ins Leere und ein dort
 * abgelegter Stapel verschwindet — die Liste ist zugleich der Mülleimer, wie in Minecraft.
 *
 * <p>Bewusste Abweichungen von MC: der Lösch-Slot existiert nur im Survival-Reiter (in den
 * Item-Reitern belegt die Scroller-Schiene genau diesen Platz — MC macht es ebenso), und
 * Shift-Klick auf einen Hotbar-Slot eines Item-Reiters tut nichts, statt das Item zu löschen
 * (Löschen geht über die Liste, versehentlich soll dabei nichts verschwinden).
 *
 * <p>Ein Gamemode-Wechsel bei offenem Screen ist heute unmöglich (die Taste dafür läuft nur ohne
 * offene GUI) und wird deshalb nicht behandelt. Käme später ein anderer Umschaltweg dazu, gehört
 * der Rückfall auf {@link GuiInventory} an den Anfang von {@link #render}.
 */
public final class GuiCreativeInventory extends GuiContainer {

    private static final int W = 195, H = 136;
    private static final float TEX = 256f;

    /** Item-Liste: 9×5 Slots ab (9,18) im Fenster. */
    private static final int LIST_COLS = 9, LIST_ROWS = 5, LIST_SIZE = LIST_COLS * LIST_ROWS;
    private static final int LIST_X = 9, LIST_Y = 18;
    /** Hotbar-Reihe (beide Reiter-Arten) bzw. Hauptinventar im Survival-Reiter. */
    private static final int HOTBAR_Y = 112, INV_Y = 54;

    /* Scroller: Schiene aus dem Sheet vermessen (x 175, y 18..127), Sprite 12×15. */
    private static final int RAIL_X = 175, RAIL_Y = LIST_Y, RAIL_H = 110;
    private static final int SCROLLER_W = 12, SCROLLER_H = 15;
    private static final int SCROLL_TRAVEL = RAIL_H - SCROLLER_H;

    /** Lösch-Slot im Survival-Reiter (Innenfläche; der Rahmen sitzt bei 172,111). */
    private static final int DELETE_X = 173, DELETE_Y = 112;

    /* Reiter: zwei Reihen à 7 Spalten, Sprite 26×32 mit Abstand 27, ragt 4 px ins Fenster.
       Plätze werden über einen flachen Index 0..13 adressiert: 0..6 oben, 7..13 unten. */
    private static final int TAB_W = 26, TAB_H = 32, TAB_PITCH = 27;
    private static final int TAB_COLS = 7;
    /** Spalten 0..4 sitzen linksbündig, 5 und 6 rechtsbündig (siehe {@link #tabX}). */
    private static final int TAB_LEFT_COLS = 5;
    private static final int TAB_SLOTS = TAB_COLS * 2;
    private static final int TAB_TOP_DY = -28;

    /* Angeheftete Sonder-Reiter: Suche oben rechts, Survival-Inventar unten rechts (wie MC).
       Was übrig bleibt, sind die 12 blätterbaren Plätze der beiden linken Spaltenblöcke. */
    private static final int SLOT_SEARCH = TAB_COLS - 1;
    private static final int SLOT_INVENTORY = 2 * TAB_COLS - 1;
    private static final int PAGE_SLOTS = 2 * (TAB_COLS - 1);

    /* Höhe der Reiter-Symbole, gemessen als Mitte der SICHTBAREN Innenfläche des Sprites:
       oben liegt sie bei y 5..27 (Mitte 16), unten bei y 4..24 (Mitte 14) — je 4 px des
       26×32-Sprites stecken hinter dem Fenster, oben die unteren, unten die oberen.
       Größerer Wert = weiter unten. HIER drehen, wenn die Symbole zu hoch oder zu tief sitzen. */
    private static final float TAB_ICON_Y_TOP = 16;
    private static final float TAB_ICON_Y_BOTTOM = 14;

    /** Seiten-Leiste über der oberen Reiter-Reihe (nur sichtbar, wenn es mehr als eine gibt). */
    private static final int PAGER_H = 20, PAGER_GAP = 4;
    private static final float PAGER_TEXT_SIZE = GuiText.NORMAL;

    /** Suchfeld exakt über dem im Sheet gemalten Kasten (x 80..169, y 4..15). */
    private static final int SEARCH_X = 80, SEARCH_Y = 4, SEARCH_W = 90, SEARCH_H = 12;

    /* Spieler-Vorschau des Survival-Reiters. Die schwarze Fläche steht im Sheet bei x 73..104,
       y 6..48. Werte proportional aus dem bekannt guten GuiInventory abgeleitet: dort ist die
       Fläche 49×70 und der Aufruf lautet (Mitte + 1.5, Boden − 2, Scale 30) — hier also
       Mitte 88.5 + 1, Boden 48 − 1 und Scale 30 × 43/70. */
    private static final float PREVIEW_X = 89.5f, PREVIEW_FEET_Y = 47, PREVIEW_SCALE = 18;

    private static final Color4 TITLE_COLOR = new Color4(0.25f, 0.25f, 0.25f, 1f);
    private static final float TITLE_SIZE = GuiText.NORMAL;

    /* Gewählter Reiter und Reiter-Seite überleben das Schließen (wie MC). */
    private static String selectedTabId;
    private static int tabPage;

    private final ItemStorage playerInv;
    private final PlayerRenderer playerRenderer;
    private final HeldItemMeshes heldItemMeshes;
    private final Supplier<ItemStack> heldItem;

    /** Normale Item-Reiter (blätterbar) und die angehefteten Sonder-Reiter. */
    private final List<CreativeTab> pageTabs = new ArrayList<>();
    private final List<CreativeTab> pinnedTabs = new ArrayList<>();
    private final int pageCount;

    private final CreativeList list = new CreativeList();
    /** Aktuell angezeigte Items (Reiter-Inhalt bzw. Suchergebnis). */
    private List<Item> contents = List.of();
    private int rowOffset;
    private boolean scrollDragging;

    private TextField searchField;
    private String lastQuery = "";
    private Button prevPage, nextPage;

    private float guiX, guiY;

    public GuiCreativeInventory(ItemStorage playerInv, PlayerRenderer playerRenderer, HeldItemMeshes heldItemMeshes, Supplier<ItemStack> heldItem) {
        super(playerInv);
        this.playerInv = playerInv;
        this.playerRenderer = playerRenderer;
        this.heldItemMeshes = heldItemMeshes;
        this.heldItem = heldItem;

        /* Leere Item-Reiter werden ausgeblendet — der Sammel-Reiter "misc" verschwindet damit von
           selbst, sobald alles getaggt ist. Die Reiter-Anzahl ist also datenabhängig. */
        for (CreativeTab tab : CreativeTabs.tabs()) {
            if (tab.type() != CreativeTab.Type.ITEMS) {
                this.pinnedTabs.add(tab);
            } else if (!CreativeTabs.items(tab.id()).isEmpty()) {
                this.pageTabs.add(tab);
            }
        }
        this.pageCount = Math.max(1, (this.pageTabs.size() + PAGE_SLOTS - 1) / PAGE_SLOTS);

        if (this.findTab(selectedTabId) == null) {
            selectedTabId = this.pageTabs.isEmpty()
                    ? (this.pinnedTabs.isEmpty() ? null : this.pinnedTabs.getFirst().id())
                    : this.pageTabs.getFirst().id();
            tabPage = 0;
        }
        tabPage = Math.clamp(tabPage, 0, this.pageCount - 1);
    }

    /* --- Reiter --- */

    private CreativeTab findTab(String id) {
        if (id == null) return null;
        for (CreativeTab tab : this.pageTabs) {
            if (tab.id().equals(id)) return tab;
        }
        for (CreativeTab tab : this.pinnedTabs) {
            if (tab.id().equals(id)) return tab;
        }
        return null;
    }

    private CreativeTab selected() {
        return this.findTab(selectedTabId);
    }

    /**
     * Reiter auf Platz {@code slot} (0..6 obere Reihe, 7..13 untere); null = Platz leer.
     *
     * <p>Die rechte Spalte gehört den angehefteten Sonder-Reitern. Die normalen Reiter füllen
     * erst die obere Reihe komplett (6 Plätze), der Rest fällt in die untere.
     *
     * <p>Bekannte Lücke (erst ab 13 Reitern erreichbar): blättert man auf eine Seite, auf der
     * der gewählte Reiter nicht liegt, verschmilzt kein Reiter mit der Fensterkante.
     */
    private CreativeTab tabAt(int slot) {
        if (slot == SLOT_SEARCH) return this.pinned(CreativeTab.Type.SEARCH);
        if (slot == SLOT_INVENTORY) return this.pinned(CreativeTab.Type.INVENTORY);

        int col = slot % TAB_COLS;
        if (col >= TAB_COLS - 1) return null;

        List<CreativeTab> page = this.currentPageTabs();
        int topCount = Math.min(page.size(), TAB_COLS - 1);   // obere Reihe zuerst voll
        boolean top = slot < TAB_COLS;
        int i = top ? col : topCount + col;
        return i < (top ? topCount : page.size()) ? page.get(i) : null;
    }

    /** Die normalen Reiter der aktuellen Seite (höchstens {@link #PAGE_SLOTS}). */
    private List<CreativeTab> currentPageTabs() {
        int from = tabPage * PAGE_SLOTS;
        if (from >= this.pageTabs.size()) return List.of();
        return this.pageTabs.subList(from, Math.min(from + PAGE_SLOTS, this.pageTabs.size()));
    }

    private CreativeTab pinned(CreativeTab.Type type) {
        for (CreativeTab tab : this.pinnedTabs) {
            if (tab.type() == type) return tab;
        }
        return null;
    }

    private void selectTab(CreativeTab tab) {
        if (tab == null || tab.id().equals(selectedTabId)) return;
        selectedTabId = tab.id();
        this.rowOffset = 0;
        this.scrollDragging = false;
        if (this.searchField != null) {
            /* Fokus MUSS mitgehen: focusedComponent() prüft die Sichtbarkeit nicht, ein
               unsichtbar fokussiertes Feld würde weiter alle Tasten schlucken. */
            this.searchField.setFocused(tab.type() == CreativeTab.Type.SEARCH);
            this.searchField.visible = tab.type() == CreativeTab.Type.SEARCH;
        }
        this.refreshContents();
        this.rebuildSlots();
    }

    /**
     * MC-Formel: die Spalten 0..4 sitzen linksbündig am Fensterrand, die Spalten 5 und 6
     * RECHTSBÜNDIG. Nur dadurch schließt der erste Reiter bündig mit der linken und der letzte
     * bündig mit der rechten Fensterkante ab; die Lücke dazwischen ist Absicht und in Minecraft
     * identisch (an der Vorlage vermessen: 142 und 169 bei einer Fensterbreite von 195).
     */
    private float tabX(int slot) {
        int col = slot % TAB_COLS;
        int x = col < TAB_LEFT_COLS ? col * TAB_PITCH : W - TAB_PITCH * (TAB_COLS - col) + 1;
        return this.guiX + x;
    }

    private float tabY(int slot) {
        return this.guiY + (slot < TAB_COLS ? TAB_TOP_DY : H - 4);
    }

    private static boolean isTopRow(int slot) {
        return slot < TAB_COLS;
    }

    /**
     * Trefferfläche eines Reiters ohne die 4 px, die hinter dem Fenster liegen — oben sind das
     * die unteren vier, unten die oberen. Ohne diesen Zuschnitt würde ein Klick auf den obersten
     * bzw. untersten Fensterrand den dahinterliegenden Reiter treffen.
     */
    private boolean overTab(int slot, double mx, double my) {
        float x = this.tabX(slot), y = this.tabY(slot);
        float y0 = isTopRow(slot) ? y : y + 4;
        float y1 = isTopRow(slot) ? y + TAB_H - 4 : y + TAB_H;
        return mx >= x && mx < x + TAB_W && my >= y0 && my < y1;
    }

    /* --- Inhalt --- */

    private boolean hasList() {
        return this.selected() != null && this.selected().type() != CreativeTab.Type.INVENTORY;
    }

    private int maxRowOffset() {
        return Math.max(0, (this.contents.size() + LIST_COLS - 1) / LIST_COLS - LIST_ROWS);
    }

    private void refreshContents() {
        CreativeTab tab = this.selected();
        this.contents = switch (tab == null ? CreativeTab.Type.ITEMS : tab.type()) {
            case ITEMS -> CreativeTabs.items(tab.id());
            case SEARCH -> filter(this.searchField == null ? "" : this.searchField.getText());
            case INVENTORY -> List.of();
        };
        this.rowOffset = Math.clamp(this.rowOffset, 0, this.maxRowOffset());
    }

    /** Suchtreffer über Anzeigename ODER Identifier (leere Eingabe = alles). */
    private static List<Item> filter(String query) {
        String q = query.trim().toLowerCase(Locale.ROOT);
        if (q.isEmpty()) return CreativeTabs.all();
        List<Item> out = new ArrayList<>();
        for (Item item : CreativeTabs.all()) {
            if (item.getDisplayName().toLowerCase(Locale.ROOT).contains(q)
                    || item.getId().toString().contains(q)) {
                out.add(item);
            }
        }
        return out;
    }

    /**
     * Synthetisches Lager der Item-Liste. Jeder Zugriff liefert einen FRISCHEN Stapel mit Anzahl 1
     * — dadurch malt {@code StackText} keine Zahl (MC-Optik) und jede Mutation der geerbten
     * Klick-Logik trifft ein Wegwerf-Objekt. Der volle Stapel entsteht erst beim Klick.
     */
    private final class CreativeList implements ItemStorage {

        @Override
        public int size() {
            return LIST_SIZE;
        }

        @Override
        public ItemStack get(int slot) {
            int i = GuiCreativeInventory.this.rowOffset * LIST_COLS + slot;
            List<Item> items = GuiCreativeInventory.this.contents;
            return i >= 0 && i < items.size() ? new ItemStack(items.get(i), 1) : ItemStack.EMPTY;
        }

        @Override
        public void set(int slot, ItemStack stack) {
            /* Die Liste ist unveränderlich — Ablegen heißt löschen (wie in MC). */
        }

        @Override
        public ItemStack insert(ItemStack stack) {
            return ItemStack.EMPTY;   // schluckt alles
        }

        @Override
        public ItemStack extract(int slot, int amount) {
            return ItemStack.EMPTY;
        }
    }

    /* --- Layout --- */

    @Override
    public void init(GuiManager gui, float vW, float vH) {
        this.guiX = (vW - W) / 2f;
        /* Zentriert wird die GESAMTE Anordnung, nicht nur das Fenster: die Reiter ragen oben und
           unten je 28 px hinaus, mit sichtbarer Seiten-Leiste oben nochmal 24 px mehr. Ohne die
           Leiste ist das Ergebnis rechnerisch identisch zu (vH − H)/2; mit ihr liefe die Leiste
           bei der garantierten Mindesthöhe (GuiManager.MIN_VH = 240) sonst aus dem Bild. */
        float topExtra = -TAB_TOP_DY + (this.pagerVisible() ? PAGER_H + PAGER_GAP : 0);
        float bottomExtra = TAB_H - 4;
        this.guiY = (vH - (topExtra + H + bottomExtra)) / 2f + topExtra;

        this.prevPage = new Button("<", PAGER_H, PAGER_H, () -> this.turnPage(-1));
        this.nextPage = new Button(">", PAGER_H, PAGER_H, () -> this.turnPage(1));
        this.prevPage.layoutAt(this.guiX, this.pagerY());
        this.nextPage.layoutAt(this.guiX + W - PAGER_H, this.pagerY());

        CreativeTab tab = this.selected();
        boolean search = tab != null && tab.type() == CreativeTab.Type.SEARCH;
        /* Randlos: der Eingabekasten ist bereits ins Sheet gemalt (x 80..169, y 4..15) — ein
           eigener 9-Slice läge nur doppelt darüber. */
        this.searchField = new TextField(SEARCH_W, SEARCH_H, 64, null).borderless();
        this.searchField.layoutAt(Math.round(this.guiX) + SEARCH_X, Math.round(this.guiY) + SEARCH_Y);
        this.searchField.visible = search;
        this.searchField.setFocused(search);
        this.searchField.text(this.lastQuery);
        this.components.clear();
        this.components.add(this.searchField);

        this.refreshContents();
        this.rebuildSlots();
    }

    private void rebuildSlots() {
        int gx = Math.round(this.guiX), gy = Math.round(this.guiY);
        this.slots.clear();

        if (this.hasList()) {
            for (int r = 0; r < LIST_ROWS; r++)
                for (int c = 0; c < LIST_COLS; c++)
                    this.slots.add(new Slot(this.list, r * LIST_COLS + c,
                            gx + LIST_X + c * STEP, gy + LIST_Y + r * STEP, SlotGroup.CONTAINER));
        } else {
            /* Survival-Reiter: Hauptinventar (Indizes 9..35). */
            for (int r = 0; r < 3; r++)
                for (int c = 0; c < COLS; c++)
                    this.slots.add(new Slot(this.playerInv, COLS + r * COLS + c,
                            gx + LIST_X + c * STEP, gy + INV_Y + r * STEP, SlotGroup.INVENTORY));
        }
        /* Hotbar (Indizes 0..8) hat in beiden Reiter-Arten dieselbe Position. */
        for (int c = 0; c < COLS; c++) {
            this.slots.add(new Slot(this.playerInv, c, gx + LIST_X + c * STEP, gy + HOTBAR_Y,
                    SlotGroup.HOTBAR));
        }
    }

    /** Fenster INKLUSIVE beider Reiter-Reihen — sonst würde ein Reiter-Klick den Stapel auswerfen. */
    @Override
    protected boolean isInsideWindow(double mx, double my) {
        return mx >= this.guiX && mx < this.guiX + W
                && my >= this.guiY + TAB_TOP_DY && my < this.guiY + H - 4 + TAB_H;
    }

    /**
     * Die Creative-Liste ist NIE Quickmove-Ziel (sonst schöbe ein Shift-Klick Items in den
     * Mülleimer). In einem Item-Reiter gibt es kein Hauptinventar — dann hat ein Quickmove
     * schlicht kein Ziel und tut nichts.
     */
    @Override
    protected List<Slot> quickMoveTargets(SlotGroup from) {
        if (from == SlotGroup.CONTAINER) return List.of();
        List<Slot> inventory = this.slotsOf(SlotGroup.INVENTORY);
        if (inventory.isEmpty()) return List.of();
        return from == SlotGroup.HOTBAR ? inventory : this.slotsOf(SlotGroup.HOTBAR);
    }

    /* --- Eingabe --- */

    @Override
    public boolean mousePressed(GuiManager gui, double mouseX, double mouseY, int button) {
        if (this.searchField != null && this.searchField.visible) {
            boolean hit = this.searchField.mousePressed(mouseX, mouseY, button);
            this.searchField.setFocused(hit);
            if (hit) return true;
        }
        if (this.clickPager(gui, mouseX, mouseY, button)) return true;
        if (this.clickTab(mouseX, mouseY)) return true;

        if (this.isOverDelete(mouseX, mouseY)) {
            this.carried = ItemStack.EMPTY;
            return true;
        }
        if (this.hasList() && this.overRail(mouseX, mouseY)) {
            this.scrollDragging = true;
            this.scrollTo(mouseY);
            return true;
        }

        Slot slot = this.slotAt(mouseX, mouseY);
        if (slot != null && slot.group == SlotGroup.CONTAINER) {
            this.clickList(slot, button);
            return true;
        }
        return super.mousePressed(gui, mouseX, mouseY, button);
    }

    private boolean clickTab(double mx, double my) {
        for (int slot = 0; slot < TAB_SLOTS; slot++) {
            CreativeTab tab = this.tabAt(slot);
            if (tab != null && this.overTab(slot, mx, my)) {
                this.selectTab(tab);
                return true;
            }
        }
        return false;
    }

    /**
     * Klick auf die Seiten-Leiste. Die Buttons liegen nicht in {@code leaves} (GuiContainer
     * reicht Klicks nicht dorthin weiter), deshalb werden sie — wie das Suchfeld — von Hand
     * abgefragt und der UI-Klickton selbst gespielt. Die Richtungs-Prüfung steht zusätzlich zum
     * {@code enabled}-Zustand da, damit ein noch nicht aktualisiertes Widget nichts auslöst.
     */
    private boolean clickPager(GuiManager gui, double mx, double my, int button) {
        if (!this.pagerVisible()) return false;
        if (tabPage > 0 && this.prevPage.mousePressed(mx, my, button)) {
            gui.sound().playUiClick();
            return true;
        }
        if (tabPage < this.pageCount - 1 && this.nextPage.mousePressed(mx, my, button)) {
            gui.sound().playUiClick();
            return true;
        }
        return false;
    }

    private void turnPage(int delta) {
        tabPage = Math.clamp(tabPage + delta, 0, this.pageCount - 1);
    }

    private boolean pagerVisible() {
        return this.pageCount > 1;
    }

    private float pagerY() {
        return this.guiY + TAB_TOP_DY - PAGER_H - PAGER_GAP;
    }

    /**
     * Klick auf einen Listen-Slot. Mit belegter Hand ist die Liste der Mülleimer, mit leerer Hand
     * gibt sie einen vollen Stapel — Shift legt ihn direkt ins Inventar. Der Klick wird
     * vollständig hier behandelt, also nie an {@code beginDrag}/Doppelklick weitergereicht.
     */
    private void clickList(Slot slot, int button) {
        if (button != GLFW.GLFW_MOUSE_BUTTON_LEFT && button != GLFW.GLFW_MOUSE_BUTTON_RIGHT
                && button != GLFW.GLFW_MOUSE_BUTTON_MIDDLE) {
            return;   // Maus 4/5 kommen wegen capturesMouse() mit an
        }
        if (!this.carried.isEmpty()) {
            this.carried = ItemStack.EMPTY;
            return;
        }
        ItemStack stack = slot.get();
        if (stack.isEmpty()) return;

        ItemStack full = new ItemStack(stack.getItem(), stack.getMaxStackSize());
        if (SkyEngine.get().getInput().isShiftDown()) {
            this.playerInv.insert(full);   // Rest verfällt: Nachschub gibt es unbegrenzt
        } else {
            this.carried = full;
        }
    }

    @Override
    public void mouseDragged(GuiManager gui, double mouseX, double mouseY, int button) {
        if (this.scrollDragging) {
            this.scrollTo(mouseY);
            return;
        }
        /* Zug über die Liste ignorieren: DragMode.DISTRIBUTE würde den getragenen Stapel dort
           "verteilen" — er wäre weg. */
        Slot slot = this.slotAt(mouseX, mouseY);
        if (slot != null && slot.group == SlotGroup.CONTAINER) return;
        super.mouseDragged(gui, mouseX, mouseY, button);
    }

    @Override
    public void mouseReleased(GuiManager gui, double mouseX, double mouseY, int button) {
        this.scrollDragging = false;
        super.mouseReleased(gui, mouseX, mouseY, button);
    }

    /** Mausrad scrollt die Liste; im Survival-Reiter bleibt das geerbte Rad-Quickmove. */
    @Override
    public boolean mouseScrolled(GuiManager gui, double mouseX, double mouseY, double amount) {
        int max = this.maxRowOffset();
        if (!this.hasList() || max <= 0) return super.mouseScrolled(gui, mouseX, mouseY, amount);
        this.rowOffset = Math.clamp(this.rowOffset - (int) Math.signum(amount), 0, max);
        return true;
    }

    @Override
    public boolean keyPressed(GuiManager gui, int key) {
        /* Fokussiertes Suchfeld zuerst: sonst deutete die Basis die Ziffern als Hotbar-Tausch. */
        if (this.focusedComponent() != null) return super.keyPressed(gui, key);

        Slot hovered = this.slotAt(gui.mouseX(), gui.mouseY());
        if (hovered != null && hovered.group == SlotGroup.CONTAINER) {
            GameSettings settings = GameSettings.get();
            for (int i = 0; i < 9; i++) {
                if (key != settings.key(KeyBindings.hotbar(i + 1))) continue;
                ItemStack stack = hovered.get();
                /* Voller Stapel statt des geerbten Tauschs — der würde den Einzel-Stapel der
                   Liste in die Hotbar legen und den bisherigen Inhalt ins Nichts schieben. */
                if (!stack.isEmpty()) {
                    this.setHotbar(i, new ItemStack(stack.getItem(), stack.getMaxStackSize()));
                }
                return true;
            }
            /* Aus dem Nichts wird nichts geworfen. */
            if (key == settings.key(KeyBindings.DROP)) return true;
        }
        return super.keyPressed(gui, key);
    }

    private void setHotbar(int index, ItemStack stack) {
        for (Slot s : this.slots) {
            if (s.group == SlotGroup.HOTBAR && s.index == index) {
                s.set(stack);
                return;
            }
        }
    }

    /* --- Scroller --- */

    private boolean overRail(double mx, double my) {
        return mx >= this.guiX + RAIL_X && mx < this.guiX + RAIL_X + SCROLLER_W
                && my >= this.guiY + RAIL_Y && my < this.guiY + RAIL_Y + RAIL_H;
    }

    private void scrollTo(double my) {
        int max = this.maxRowOffset();
        if (max <= 0) return;
        double t = (my - (this.guiY + RAIL_Y) - SCROLLER_H / 2.0) / SCROLL_TRAVEL;
        this.rowOffset = (int) Math.round(Math.clamp(t, 0.0, 1.0) * max);
    }

    /* --- Lösch-Slot (nur im Survival-Reiter; im Item-Reiter sitzt dort die Scroller-Schiene) --- */

    private boolean isOverDelete(double mx, double my) {
        if (this.hasList()) return false;
        return mx >= this.guiX + DELETE_X && mx < this.guiX + DELETE_X + SLOT
                && my >= this.guiY + DELETE_Y && my < this.guiY + DELETE_Y + SLOT;
    }

    /* --- Zeichnen --- */

    @Override
    public void render(GuiManager gui, double mouseX, double mouseY) {
        CreativeTab tab = this.selected();
        if (tab == null) return;

        /* Suchtext hat sich geändert -> Trefferliste neu bauen (nicht jeden Frame). */
        if (tab.type() == CreativeTab.Type.SEARCH && this.searchField != null
                && !this.searchField.getText().equals(this.lastQuery)) {
            this.lastQuery = this.searchField.getText();
            this.rowOffset = 0;
            this.refreshContents();
        }

        float vW = gui.vWidth(), vH = gui.vHeight();
        SpriteRenderer sr = gui.sprites();

        sr.begin(vW, vH);
        this.renderBackground(gui);
        /* Reihenfolge erzeugt den "angehefteten" MC-Effekt: erst alle nicht gewählten Reiter
           beider Reihen, dann das Fenster darüber (es verdeckt oben deren untere 4 px und unten
           deren obere), dann der gewählte Reiter obendrauf. */
        this.drawTabs(gui, false);
        sr.drawSprite(this.windowTexture(gui, tab), this.guiX, this.guiY, W, H, 0, 0, W / TEX, H / TEX);
        this.drawTabs(gui, true);
        this.drawPager(gui, mouseX, mouseY);
        if (this.hasList()) this.drawScroller(gui);
        if (this.searchField != null && this.searchField.visible) {
            this.searchField.updateHover(mouseX, mouseY);
            this.searchField.renderBackground(gui, mouseX, mouseY);
        }
        this.drawSlotHover(gui, mouseX, mouseY);
        sr.end();

        if (tab.type() == CreativeTab.Type.INVENTORY) {
            this.playerRenderer.renderPreview(this.guiX + PREVIEW_X, this.guiY + PREVIEW_FEET_Y,
                    PREVIEW_SCALE, mouseX, mouseY, vW, vH, this.heldItemMeshes, this.heldItem.get());
        }

        this.drawTabIcons(gui, vH);

        gui.font().begin(vW, vH);
        /* Auch der Such-Reiter trägt seine Beschriftung — sie steht links neben dem gemalten
           Eingabekasten (MC-Optik). Nur der Survival-Reiter bleibt ohne Titel. */
        if (tab.type() != CreativeTab.Type.INVENTORY) {
            gui.font().drawString(I18n.tr(tab.translationKey()),
                    this.guiX + 8, this.guiY + 6, TITLE_SIZE, TITLE_COLOR);
        }
        if (this.searchField != null && this.searchField.visible) {
            this.searchField.renderText(gui, mouseX, mouseY);
        }
        this.drawPagerText(gui, mouseX, mouseY);
        gui.font().end();

        this.drawSlotIcons(gui, mouseX, mouseY);
        this.drawTooltip(gui, mouseX, mouseY);
    }

    private Texture windowTexture(GuiManager gui, CreativeTab tab) {
        return switch (tab.type()) {
            case ITEMS -> gui.textures().creativeTabItems;
            case SEARCH -> gui.textures().creativeTabSearch;
            case INVENTORY -> gui.textures().creativeTabInventory;
        };
    }

    /** Zeichnet entweder alle NICHT gewählten Reiter oder nur den gewählten. */
    private void drawTabs(GuiManager gui, boolean selectedPass) {
        for (int slot = 0; slot < TAB_SLOTS; slot++) {
            CreativeTab tab = this.tabAt(slot);
            if (tab == null) continue;
            boolean isSelected = tab.id().equals(selectedTabId);
            if (isSelected != selectedPass) continue;

            gui.sprites().drawSprite(this.tabSprite(gui, slot, isSelected),
                    this.tabX(slot), this.tabY(slot), TAB_W, TAB_H);

            /* Der Such-Reiter hat kein Item als Symbol — dieses Projekt kennt keinen Kompass. */
            if (tab.type() == CreativeTab.Type.SEARCH) {
                gui.sprites().drawSprite(gui.textures().iconSearch,
                        this.tabX(slot) + 7, this.tabIconCenterY(slot) - 6, 12, 12);
            }
        }
    }

    /** Sprite eines Reiters: Reihe entscheidet über oben/unten, die SPALTE über die Randform. */
    private Texture tabSprite(GuiManager gui, int slot, boolean selected) {
        GuiTextures t = gui.textures();
        int col = slot % TAB_COLS;
        if (isTopRow(slot)) {
            if (!selected) return t.creativeTabTopUnselected;
            return col == 0 ? t.creativeTabTopSelectedLeft
                    : col == TAB_COLS - 1 ? t.creativeTabTopSelectedRight
                    : t.creativeTabTopSelectedMid;
        }
        if (!selected) return t.creativeTabBottomUnselected;
        return col == 0 ? t.creativeTabBottomSelectedLeft
                : col == TAB_COLS - 1 ? t.creativeTabBottomSelectedRight
                : t.creativeTabBottomSelectedMid;
    }

    /** Mitte der sichtbaren Reiter-Fläche; Feinjustierung über {@link #TAB_ICON_Y_TOP}. */
    private float tabIconCenterY(int slot) {
        return this.tabY(slot) + (isTopRow(slot) ? TAB_ICON_Y_TOP : TAB_ICON_Y_BOTTOM);
    }

    private void drawTabIcons(GuiManager gui, float vH) {
        gui.icons().begin(gui.vWidth(), vH);
        for (int slot = 0; slot < TAB_SLOTS; slot++) {
            CreativeTab tab = this.tabAt(slot);
            if (tab == null || tab.type() == CreativeTab.Type.SEARCH || tab.icon() == null) continue;
            Item icon = Items.get(tab.icon());
            if (icon == null) continue;
            gui.icons().drawIcon(new ItemStack(icon, 1), this.tabX(slot) + TAB_W / 2f,
                    this.tabIconCenterY(slot), SLOT, vH);
        }
        gui.icons().end();
    }

    /**
     * Blätter-Buttons im Sprite-Pass. Sie hängen nicht in {@code leaves}, also müssen
     * {@code enabled} und {@code updateHover} hier von Hand nachgezogen werden — genau wie beim
     * Suchfeld. Am Anfang bzw. Ende der Seitenfolge sind sie ausgegraut statt ausgeblendet,
     * damit die Leiste nicht springt.
     */
    private void drawPager(GuiManager gui, double mx, double my) {
        if (!this.pagerVisible()) return;
        this.prevPage.enabled = tabPage > 0;
        this.nextPage.enabled = tabPage < this.pageCount - 1;
        this.prevPage.updateHover(mx, my);
        this.nextPage.updateHover(mx, my);
        this.prevPage.renderBackground(gui, mx, my);
        this.nextPage.renderBackground(gui, mx, my);
    }

    /** Button-Beschriftungen + Seitenzahl im Font-Pass (Text auf der Welt -> mit Schatten). */
    private void drawPagerText(GuiManager gui, double mx, double my) {
        if (!this.pagerVisible()) return;
        this.prevPage.renderText(gui, mx, my);
        this.nextPage.renderText(gui, mx, my);

        String label = (tabPage + 1) + " / " + this.pageCount;
        float tx = this.guiX + (W - gui.font().getStringWidth(label, PAGER_TEXT_SIZE)) / 2f;
        float ty = this.pagerY() + (PAGER_H - gui.font().lineHeight(PAGER_TEXT_SIZE)) / 2f;
        gui.font().drawStringWithShadow(label, tx, ty, PAGER_TEXT_SIZE, Colors.WHITE);
    }

    private void drawScroller(GuiManager gui) {
        int max = this.maxRowOffset();
        Texture sprite = max > 0 ? gui.textures().creativeScroller : gui.textures().creativeScrollerDisabled;
        float t = max > 0 ? (float) this.rowOffset / max : 0f;
        gui.sprites().drawSprite(sprite, this.guiX + RAIL_X, this.guiY + RAIL_Y + t * SCROLL_TRAVEL,
                SCROLLER_W, SCROLLER_H);
    }

    /** Reiter- und Lösch-Slot-Tooltips; die Slot-Tooltips zeichnet {@code drawTooltip}. */
    @Override
    public List<RichText> tooltipAt(double mouseX, double mouseY) {
        if (!this.carried.isEmpty()) return null;
        for (int slot = 0; slot < TAB_SLOTS; slot++) {
            CreativeTab tab = this.tabAt(slot);
            if (tab != null && this.overTab(slot, mouseX, mouseY)) {
                return List.of(RichText.plain(I18n.tr(tab.translationKey()), Colors.WHITE));
            }
        }
        if (this.isOverDelete(mouseX, mouseY)) {
            return List.of(RichText.plain(I18n.tr("creative.delete"), Colors.WHITE));
        }
        return null;
    }

    /** Im Creative verschwindet der getragene Stapel — er wird NICHT in die Welt geworfen. */
    @Override
    public void onClose() {
        this.carried = ItemStack.EMPTY;
    }
}
