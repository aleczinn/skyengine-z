package de.skyengine.graphics.gui;

import de.skyengine.audio.SoundManager;
import de.skyengine.core.input.Input;
import de.skyengine.game.entity.EntityPlayer;
import de.skyengine.core.settings.GameSettings;
import de.skyengine.core.settings.KeyBindings;
import de.skyengine.game.world.block.entity.SimpleItemStorage;
import de.skyengine.graphics.blockentity.BlockEntityRenderDispatcher;
import de.skyengine.graphics.gui.font.FontRenderer;
import de.skyengine.graphics.texture.TextureArray;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL11;

/**
 * Zentrale Sammelstelle für alles GUI/HUD: bündelt {@link SpriteRenderer} (2D), {@link ItemIconRenderer}
 * (3D-Icons), {@link GuiTextures}, das {@link Hud} und den aktuell offenen {@link GuiScreen}.
 *
 * <ul>
 *   <li><b>GUI-Scaling</b>: arbeitet in einem virtuellen Koordinatenraum {@code vW=screenW/scale},
 *       {@code vH=screenH/scale}; die Renderer projizieren darüber, sodass die ganze GUI einheitlich
 *       skaliert. Mauskoordinaten werden durch {@code scale} geteilt.</li>
 *   <li><b>Cursor</b> zustandsgesteuert: offener GuiScreen ⇒ CURSOR_NORMAL, sonst CURSOR_DISABLED
 *       (idempotent, nur bei Wechsel) — behebt den „Maus klebt in der Mitte"-Bug.</li>
 * </ul>
 */
public final class GuiManager {

    private final Input input;
    private final SoundManager sound;

    private final SpriteRenderer sprites = new SpriteRenderer();
    private final ItemIconRenderer icons = new ItemIconRenderer();
    private final FontRenderer font = new FontRenderer();
    private final GuiTextures textures = new GuiTextures();
    private final Hud hud = new Hud();

    /* Garantierte virtuelle Mindestfläche: Bei kleinen Fenstern wird der Scale automatisch
       reduziert (wie MCs Auto-GUI-Scale), damit Menüs/Hotbar IMMER komplett passen.
       MIN_VH deckt das höchste Fenster ab — die Doppeltruhe ist mit 222 px das größte;
       240 ist derselbe Wert, den auch Minecraft garantiert. */
    private static final float MIN_VW = 340, MIN_VH = 240;

    private GuiScreen screen;
    /** Gewünschter Scale aus den Settings — Obergrenze für {@link #effectiveScale}. */
    private float scale = 3.5f;
    /** Tatsächlich angewandter Scale (Wunschwert, geklemmt auf die Mindest-vW/vH). */
    private float effectiveScale = 3.5f;
    private float vW, vH;

    /* Für welche vW/vH der offene GuiScreen zuletzt layoutet wurde (NaN = init steht aus). */
    private float layoutVW = Float.NaN, layoutVH = Float.NaN;

    /* Fensterhöhe in Pixeln (für die virtuell->Pixel-Umrechnung des Scissors, y-Flip). */
    private int screenHpx;

    private int lastCursorMode = -1; // -1 = unbekannt, 0 = disabled, 1 = normal

    public GuiManager(Input input, SoundManager sound) {
        this.input = input;
        this.sound = sound;
    }

    /** Früher Boot-Anteil: nur die block-unabhängigen 2D-Renderer (für den Boot-Ladebildschirm). */
    public void initEarly() {
        this.sprites.init();
        this.font.init();
    }

    /** Später Boot-Anteil: braucht das gebaute Block-Atlas (Icons) + lädt die GUI-Texturen. */
    public void initLate(TextureArray blockTextures, BlockEntityRenderDispatcher blockEntityRenderers) {
        this.icons.init(blockTextures, blockEntityRenderers);
        this.textures.init();
    }

    /* --- Zugriff für Screens/HUD --- */
    public SpriteRenderer sprites() {
        return this.sprites;
    }

    public SoundManager sound() {
        return this.sound;
    }

    public ItemIconRenderer icons() {
        return this.icons;
    }

    public FontRenderer font() {
        return this.font;
    }

    public GuiTextures textures() {
        return this.textures;
    }

    public float vWidth() {
        return this.vW;
    }

    public float vHeight() {
        return this.vH;
    }

    public void setScale(float scale) {
        this.scale = Math.max(1f, scale);
    }

    public boolean isOpen() {
        return this.screen != null;
    }

    /** true, wenn der offene GuiScreen die Welt pausiert (Pause-Menü). */
    public boolean pausesGame() {
        return this.screen != null && this.screen.doesPausesGame();
    }

    /** true, wenn der offene GuiScreen gerade alle Tasten exklusiv beansprucht (Keybind-Aufnahme). */
    public boolean capturesKeys() {
        return this.screen != null && this.screen.capturesKeys();
    }

    /** true, wenn der offene GuiScreen die Szene dahinter blurt (Pause-Menü + Unterseiten). */
    public boolean blursBackground() {
        return this.screen != null && this.screen.blursBackground();
    }

    public GuiScreen current() {
        return this.screen;
    }

    /** Erzwingt ein Re-Init des offenen Screens im nächsten Frame (z. B. nach Sprachwechsel). */
    public void relayoutCurrent() {
        this.layoutVW = Float.NaN;
    }

    /**
     * Öffnet einen GuiScreen (ersetzt ggf. den aktuellen, dessen onClose dann läuft —
     * so speichert z.B. ein Optionsmenü beim Zurück-Navigieren). Das Layout ({@code init})
     * passiert im nächsten {@link #render}, wenn vW/vH sicher aktuell sind.
     */
    public void open(GuiScreen screen) {
        if (this.screen != null && this.screen != screen) {
            this.screen.onClose();
        }
        this.screen = screen;
        this.layoutVW = Float.NaN; // init im nächsten render erzwingen
    }

    public void close() {
        if (this.screen != null) {
            this.screen.onClose();
            this.screen = null;
        }
    }

    public double mouseX() {
        return this.input.getMouseX() / this.effectiveScale;
    }

    public double mouseY() {
        return this.input.getMouseY() / this.effectiveScale;
    }

    /**
     * Routet alle Eingaben an den offenen GuiScreen: Tasten (inkl. Default-ESC im GuiScreen),
     * Text-Eingabe, Maus-Druck/-Loslassen/-Ziehen und Scrollen. Die Inventar-Taste schließt
     * nur Container-Screens ({@link GuiScreen#closesOnInventoryKey()}) — und nur, wenn kein
     * Widget (z.B. Textfeld) die Taste konsumiert hat.
     */
    public void handleInput() {
        /* Ein gerade geöffneter Screen ist noch NICHT initialisiert (init läuft erst im render()).
           Keine Eingaben an ihn routen, bis sein Layout steht — sonst NPE auf init-Feldern (z.B.
           GuiSelectWorld.rows), wenn ein mousePressed mitten in der Schleife einen neuen Screen
           öffnet und derselbe Frame noch ein mouseDragged an ihn schickt. */
        if (!this.screenReady()) return;
        double mx = this.mouseX(), my = this.mouseY();

        /* Tasten: erst der GuiScreen (fokussiertes Widget, Default-ESC), dann die Schließ-Taste. */
        int inventoryKey = GameSettings.get().key(KeyBindings.OPEN_INVENTORY);
        this.input.forEachKeyPressedThisFrame(key -> {
            if (!this.screenReady()) return; // Screen geschlossen ODER ein Handler öffnete einen neuen
            if (this.screen.keyPressed(this, key)) return;
            if (key == inventoryKey && this.screen.closesOnInventoryKey()) {
                this.close();
            }
        });
        if (!this.screenReady()) return;

        /* Text-Eingabe (in Frame-Reihenfolge nach den Key-Events unkritisch, s. Input). */
        for (int i = 0; i < this.input.charCount() && this.screenReady(); i++) {
            this.screen.charTyped(this, this.input.charAt(i));
        }
        if (!this.screenReady()) return;

        /* Maus: Druck/Loslassen/Ziehen, Scrollen. Links/rechts gehen immer an den Screen, die
           übrigen GLFW-Buttons (Mitte, Maus 4/5, …) nur, wenn er sie ausdrücklich beansprucht
           (Keybind-Aufnahme) — sonst würden Slot-Klicks/Scrollbars auf Zusatztasten reagieren.
           Nach jedem Handler prüfen: ein mousePressed kann einen neuen (noch nicht layouteten)
           Screen geöffnet haben — dann NICHT weiter routen (sonst mouseDragged auf null-Felder
           des neuen Screens). */
        for (int button = 0; button <= GLFW.GLFW_MOUSE_BUTTON_LAST; button++) {
            if (button > GLFW.GLFW_MOUSE_BUTTON_RIGHT && !this.screen.capturesMouse()) continue;
            if (this.input.isMousePressed(button)) {
                this.screen.mousePressed(this, mx, my, button);
            }
            if (!this.screenReady()) return;
            if (this.input.isMouseReleased(button)) {
                this.screen.mouseReleased(this, mx, my, button);
            }
            if (!this.screenReady()) return;
            if (this.input.isMouseDown(button)
                    && (this.input.getDeltaMouseX() != 0 || this.input.getDeltaMouseY() != 0)) {
                this.screen.mouseDragged(this, mx, my, button);
            }
            if (!this.screenReady()) return;
        }
        double scroll = this.input.getScrollY();
        if (scroll != 0) {
            this.screen.mouseScrolled(this, mx, my, scroll);
        }
    }

    /** true, wenn ein Screen offen UND bereits initialisiert/layoutet ist (init läuft erst im render). */
    private boolean screenReady() {
        return this.screen != null && !Float.isNaN(this.layoutVW);
    }

    /**
     * Pro Frame nach der Welt: Cursor synchronisieren + ggf. GuiScreen + Hotbar zeichnen.
     * Die Hotbar wird IMMER gerendert (auch bei offenem Inventar, wie in Minecraft) und teilt sich die
     * Daten mit dem GuiScreen (gleiches Spielerinventar) -> automatisch synchron. Das Fadenkreuz nur ohne
     * GuiScreen und nur, wenn der Aufrufer es will (First Person).
     */
    public void render(int screenW, int screenH, SimpleItemStorage hotbarInv, int selectedSlot,
                       boolean showHotbar, boolean crosshair, float itemNameAlpha, EntityPlayer player) {
        this.syncCursor();
        this.screenHpx = screenH;
        /* Auto-Scale: bei kleinen Fenstern den Scale reduzieren, damit die virtuelle Fläche
           nie unter MIN_VW×MIN_VH fällt (UI schrumpft mit, statt abgeschnitten zu werden). */
        this.effectiveScale = Math.max(1f, Math.min(this.scale,
                Math.min(screenW / MIN_VW, screenH / MIN_VH)));
        this.vW = screenW / this.effectiveScale;
        this.vH = screenH / this.effectiveScale;

        /* HUD ZUERST (wie in Minecraft): ein offener GuiScreen samt Dim liegt ÜBER der Hotbar —
           sonst übermalt die Hotbar z.B. die Footer-Buttons von Scroll-Menüs. */
        if (hotbarInv != null) {
            this.hud.render(this, hotbarInv, selectedSlot, this.screen == null && crosshair, showHotbar, itemNameAlpha, player);
        }
        if (this.screen != null) {
            /* Layout beim Öffnen und bei jeder Größen-/Scale-Änderung (statt pro Frame):
               init baut die Widgets, layout() verankert Stacks + flacht den Baum ab. */
            if (this.vW != this.layoutVW || this.vH != this.layoutVH) {
                this.screen.init(this, this.vW, this.vH);
                this.screen.layout(this.vW, this.vH);
                this.layoutVW = this.vW;
                this.layoutVH = this.vH;
            }
            double mx = this.mouseX(), my = this.mouseY();
            GuiScreen current = this.screen;
            current.render(this, mx, my);
            /* Widget-Tooltips zentral NACH dem Screen: nur hier ist garantiert, dass kein
               Sprite-/Font-Pass mehr offen ist — und es gilt auch für Screens mit eigenem
               render()-Override (davon gibt es mehrere).
               Die Prüfung ist PFLICHT und keine Redundanz: ein Screen darf sich in seinem
               eigenen render() schließen (GuiWorldLoading tut das, sobald die Welt geladen ist)
               oder einen anderen öffnen — dann ist this.screen null bzw. der neue Screen noch
               nicht layoutet. */
            if (this.screen == current && this.screenReady()) {
                Tooltip.draw(this, current.tooltipAt(mx, my), mx, my);
            }
        }
    }

    /**
     * Clippt nachfolgende GUI-Draws auf ein Rechteck im virtuellen Raum (Scroll-Listen).
     * glScissor erwartet Fenster-Pixel mit Ursprung unten links -> Umrechnung + y-Flip hier.
     * Achtung: FontRenderer flusht erst bei end() — Text, der geclippt werden soll, braucht
     * ein eigenes begin/end-Paar INNERHALB von enable/disableScissor.
     */
    public void enableScissor(float vx, float vy, float vw, float vh) {
        GL11.glEnable(GL11.GL_SCISSOR_TEST);
        GL11.glScissor(Math.round(vx * this.effectiveScale),
                Math.round(this.screenHpx - (vy + vh) * this.effectiveScale),
                Math.round(vw * this.effectiveScale),
                Math.round(vh * this.effectiveScale));
    }

    public void disableScissor() {
        GL11.glDisable(GL11.GL_SCISSOR_TEST);
    }

    private void syncCursor() {
        int want = this.screen != null ? 1 : 0;
        if (want != this.lastCursorMode) {
            if (want == 1) {
                /* Cursor mittig starten (wie MC) — Freigeben und Zentrieren MÜSSEN derselbe
                   Main-Thread-Task sein, sonst sieht man den Zeiger dazwischen kurz an der von
                   GLFW wiederhergestellten Position. Zentriert wird nur, wenn der Cursor vorher
                   im Spiel gefangen war (0 -> 1): beim Spielstart (Hauptmenü,
                   lastCursorMode == -1) darf die Maus nicht wegspringen. */
                this.input.showCursor(this.lastCursorMode == 0);
            } else {
                this.input.disableCursor();
            }
            this.lastCursorMode = want;
        }
    }

    public void dispose() {
        this.sprites.dispose();
        this.icons.dispose();
        this.font.dispose();
        this.textures.dispose();
    }
}
