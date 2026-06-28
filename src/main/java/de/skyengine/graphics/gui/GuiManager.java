package de.skyengine.graphics.gui;

import de.skyengine.core.input.Input;
import de.skyengine.core.settings.GameSettings;
import de.skyengine.core.settings.KeyBindings;
import de.skyengine.game.world.block.entity.SimpleItemStorage;
import de.skyengine.graphics.blockentity.BlockEntityRenderDispatcher;
import de.skyengine.graphics.texture.TextureArray;
import org.lwjgl.glfw.GLFW;

/**
 * Zentrale Sammelstelle für alles GUI/HUD: bündelt {@link SpriteRenderer} (2D), {@link ItemIconRenderer}
 * (3D-Icons), {@link GuiTextures}, das {@link Hud} und den aktuell offenen {@link Screen}.
 *
 * <ul>
 *   <li><b>GUI-Scaling</b>: arbeitet in einem virtuellen Koordinatenraum {@code vW=screenW/scale},
 *       {@code vH=screenH/scale}; die Renderer projizieren darüber, sodass die ganze GUI einheitlich
 *       skaliert. Mauskoordinaten werden durch {@code scale} geteilt.</li>
 *   <li><b>Cursor</b> zustandsgesteuert: offener Screen ⇒ CURSOR_NORMAL, sonst CURSOR_DISABLED
 *       (idempotent, nur bei Wechsel) — behebt den „Maus klebt in der Mitte"-Bug.</li>
 * </ul>
 */
public final class GuiManager {

    private final Input input;

    private final SpriteRenderer sprites = new SpriteRenderer();
    private final ItemIconRenderer icons = new ItemIconRenderer();
    private final GuiTextures textures = new GuiTextures();
    private final Hud hud = new Hud();

    private Screen screen;
    private float scale = 3.5f;
    private float vW, vH;

    private int lastCursorMode = -1; // -1 = unbekannt, 0 = disabled, 1 = normal

    public GuiManager(Input input) {
        this.input = input;
    }

    public void init(TextureArray blockTextures, BlockEntityRenderDispatcher blockEntityRenderers) {
        this.sprites.init();
        this.icons.init(blockTextures, blockEntityRenderers);
        this.textures.init();
    }

    /* --- Zugriff für Screens/HUD --- */
    public SpriteRenderer sprites() { return this.sprites; }
    public ItemIconRenderer icons() { return this.icons; }
    public GuiTextures textures() { return this.textures; }
    public float vWidth() { return this.vW; }
    public float vHeight() { return this.vH; }

    public void setScale(float scale) { this.scale = Math.max(1f, scale); }

    public boolean isOpen() { return this.screen != null; }

    public void open(Screen screen) { this.screen = screen; }

    public void close() {
        if (this.screen != null) {
            this.screen.onClose();
            this.screen = null;
        }
    }

    public double mouseX() { return this.input.getMouseX() / this.scale; }
    public double mouseY() { return this.input.getMouseY() / this.scale; }

    /** Eingaben bei offenem Screen: Schließen (Inventar-Taste/ESC) + Maus-Slot-Klicks. */
    public void handleInput() {
        if (this.screen == null) return;
        if (this.input.isKeyPressed(GameSettings.get().key(KeyBindings.OPEN_INVENTORY))
                || this.input.isKeyPressed(GLFW.GLFW_KEY_ESCAPE)) {
            this.close();
            return;
        }
        if (this.input.isMousePressed(GLFW.GLFW_MOUSE_BUTTON_LEFT)) {
            this.screen.mouseClicked(this.mouseX(), this.mouseY(), GLFW.GLFW_MOUSE_BUTTON_LEFT);
        } else if (this.input.isMousePressed(GLFW.GLFW_MOUSE_BUTTON_RIGHT)) {
            this.screen.mouseClicked(this.mouseX(), this.mouseY(), GLFW.GLFW_MOUSE_BUTTON_RIGHT);
        }
    }

    /**
     * Pro Frame nach der Welt: Cursor synchronisieren + ggf. Screen + Hotbar zeichnen.
     * Die Hotbar wird IMMER gerendert (auch bei offenem Inventar, wie in Minecraft) und teilt sich die
     * Daten mit dem Screen (gleiches Spielerinventar) -> automatisch synchron. Das Fadenkreuz nur ohne Screen.
     */
    public void render(int screenW, int screenH, SimpleItemStorage hotbarInv, int selectedSlot, boolean showHotbar) {
        this.syncCursor();
        this.vW = screenW / this.scale;
        this.vH = screenH / this.scale;

        if (this.screen != null) {
            this.screen.render(this, this.mouseX(), this.mouseY());
        }
        this.hud.render(this, hotbarInv, selectedSlot, this.screen == null, showHotbar);
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
        this.textures.dispose();
    }
}
