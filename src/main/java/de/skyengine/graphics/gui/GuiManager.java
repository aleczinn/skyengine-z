package de.skyengine.graphics.gui;

import de.skyengine.audio.SoundManager;
import de.skyengine.core.input.Input;
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

    private GuiScreen screen;
    private float scale = 3.5f;
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
        return this.screen != null && this.screen.pausesGame();
    }

    /** true, wenn der offene GuiScreen gerade alle Tasten exklusiv beansprucht (Keybind-Aufnahme). */
    public boolean capturesKeys() {
        return this.screen != null && this.screen.capturesKeys();
    }

    public GuiScreen current() {
        return this.screen;
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
        return this.input.getMouseX() / this.scale;
    }

    public double mouseY() {
        return this.input.getMouseY() / this.scale;
    }

    /**
     * Routet alle Eingaben an den offenen GuiScreen: Tasten (inkl. Default-ESC im GuiScreen),
     * Text-Eingabe, Maus-Druck/-Loslassen/-Ziehen und Scrollen. Die Inventar-Taste schließt
     * nur Container-Screens ({@link GuiScreen#closesOnInventoryKey()}) — und nur, wenn kein
     * Widget (z.B. Textfeld) die Taste konsumiert hat.
     */
    public void handleInput() {
        if (this.screen == null) return;
        double mx = this.mouseX(), my = this.mouseY();

        /* Tasten: erst der GuiScreen (fokussiertes Widget, Default-ESC), dann die Schließ-Taste. */
        int inventoryKey = GameSettings.get().key(KeyBindings.OPEN_INVENTORY);
        this.input.forEachKeyPressedThisFrame(key -> {
            if (this.screen == null) return; // GuiScreen wurde von einer vorherigen Taste geschlossen
            if (this.screen.keyPressed(this, key)) return;
            if (key == inventoryKey && this.screen.closesOnInventoryKey()) {
                this.close();
            }
        });
        if (this.screen == null) return;

        /* Text-Eingabe (in Frame-Reihenfolge nach den Key-Events unkritisch, s. Input). */
        for (int i = 0; i < this.input.charCount() && this.screen != null; i++) {
            this.screen.charTyped(this, this.input.charAt(i));
        }
        if (this.screen == null) return;

        /* Maus: Druck/Loslassen/Ziehen für links/rechts, Scrollen. */
        for (int button : MOUSE_BUTTONS) {
            if (this.input.isMousePressed(button)) {
                this.screen.mousePressed(this, mx, my, button);
            }
            if (this.screen == null) return;
            if (this.input.isMouseReleased(button)) {
                this.screen.mouseReleased(this, mx, my, button);
            }
            if (this.screen == null) return;
            if (this.input.isMouseDown(button)
                    && (this.input.getDeltaMouseX() != 0 || this.input.getDeltaMouseY() != 0)) {
                this.screen.mouseDragged(this, mx, my, button);
            }
            if (this.screen == null) return;
        }
        double scroll = this.input.getScrollY();
        if (scroll != 0) {
            this.screen.mouseScrolled(this, mx, my, scroll);
        }
    }

    private static final int[] MOUSE_BUTTONS = {GLFW.GLFW_MOUSE_BUTTON_LEFT, GLFW.GLFW_MOUSE_BUTTON_RIGHT};

    /**
     * Pro Frame nach der Welt: Cursor synchronisieren + ggf. GuiScreen + Hotbar zeichnen.
     * Die Hotbar wird IMMER gerendert (auch bei offenem Inventar, wie in Minecraft) und teilt sich die
     * Daten mit dem GuiScreen (gleiches Spielerinventar) -> automatisch synchron. Das Fadenkreuz nur ohne GuiScreen.
     */
    public void render(int screenW, int screenH, SimpleItemStorage hotbarInv, int selectedSlot, boolean showHotbar) {
        this.syncCursor();
        this.screenHpx = screenH;
        this.vW = screenW / this.scale;
        this.vH = screenH / this.scale;

        /* HUD ZUERST (wie in Minecraft): ein offener GuiScreen samt Dim liegt ÜBER der Hotbar —
           sonst übermalt die Hotbar z.B. die Footer-Buttons von Scroll-Menüs. */
        if (hotbarInv != null) {
            this.hud.render(this, hotbarInv, selectedSlot, this.screen == null, showHotbar);
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
            this.screen.render(this, this.mouseX(), this.mouseY());
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
        GL11.glScissor(Math.round(vx * this.scale),
                Math.round(this.screenHpx - (vy + vh) * this.scale),
                Math.round(vw * this.scale),
                Math.round(vh * this.scale));
    }

    public void disableScissor() {
        GL11.glDisable(GL11.GL_SCISSOR_TEST);
    }

    private void syncCursor() {
        int want = this.screen != null ? 1 : 0;
        if (want != this.lastCursorMode) {
            if (want == 1) this.input.showCursor();
            else this.input.disableCursor();
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
