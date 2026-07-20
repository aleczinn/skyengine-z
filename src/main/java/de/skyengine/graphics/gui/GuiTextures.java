package de.skyengine.graphics.gui;

import de.skyengine.core.file.FileHandle;
import de.skyengine.core.file.FileType;
import de.skyengine.graphics.texture.Texture;
import de.skyengine.graphics.texture.TextureFilter;

/**
 * Lädt und hält die (MC-kompatiblen) GUI-Texturen als einzelne {@link Texture} (NEAREST, ohne Mipmaps).
 * Pfade entsprechen dem Minecraft-Layout unter {@code game/textures/gui/...}.
 */
public final class GuiTextures {

    public Texture chestBackground;
    public Texture inventoryBackground;
    public Texture hotbar;
    public Texture hotbarSelection;
    public Texture crosshair;

    /* Widget-Sprites (9-Slice, Rand 3 px) */
    public Texture button;
    public Texture buttonHighlighted;
    public Texture buttonDisabled;
    public Texture slider;
    public Texture sliderHighlighted;
    public Texture sliderHandle;
    public Texture sliderHandleHighlighted;

    public Texture textField;
    public Texture textFieldHighlighted;

    /* Gekachelter Hintergrund für Titel-/Ladebildschirm (32er-Kacheln) */
    public Texture menuBackground;
    /* Optionales Vollbild-Hintergrundbild fürs Hauptmenü (object-cover); null, wenn die
       Datei fehlt -> Kachel-Fallback. */
    public Texture menuBackgroundImage;

    public void init() {
        this.chestBackground = load("game/textures/gui/container/generic_54.png");
        this.inventoryBackground = load("game/textures/gui/container/inventory.png");
        this.hotbar = load("game/textures/gui/sprites/hud/hotbar.png");
        this.hotbarSelection = load("game/textures/gui/sprites/hud/hotbar_selection.png");
        this.crosshair = load("game/textures/gui/sprites/hud/crosshair.png");

        this.button = load("game/textures/gui/sprites/widget/button.png");
        this.buttonHighlighted = load("game/textures/gui/sprites/widget/button_highlighted.png");
        this.buttonDisabled = load("game/textures/gui/sprites/widget/button_disabled.png");
        this.slider = load("game/textures/gui/sprites/widget/slider.png");
        this.sliderHighlighted = load("game/textures/gui/sprites/widget/slider_highlighted.png");
        this.sliderHandle = load("game/textures/gui/sprites/widget/slider_handle.png");
        this.sliderHandleHighlighted = load("game/textures/gui/sprites/widget/slider_handle_highlighted.png");
        this.textField = load("game/textures/gui/sprites/widget/text_field.png");
        this.textFieldHighlighted = load("game/textures/gui/sprites/widget/text_field_highlighted.png");
        this.menuBackground = load("game/textures/gui/menu_background.png");

        /* Fehlertolerant: das Bild ist optional (User-Asset). Mipmaps + trilinear,
           weil es beim object-cover-Zeichnen herunterskaliert wird. */
        FileHandle image = new FileHandle("game/menu/main_menu.png", FileType.RESOURCE);
        if (image.exists()) {
            this.menuBackgroundImage = new Texture(image, true);
            this.menuBackgroundImage.setFilter(TextureFilter.MIPMAP, TextureFilter.LINEAR);
        }
    }

    private static Texture load(String path) {
        return new Texture(new FileHandle(path, FileType.RESOURCE), false);
    }

    public void dispose() {
        if (this.chestBackground != null) this.chestBackground.dispose();
        if (this.inventoryBackground != null) this.inventoryBackground.dispose();
        if (this.hotbar != null) this.hotbar.dispose();
        if (this.hotbarSelection != null) this.hotbarSelection.dispose();
        if (this.crosshair != null) this.crosshair.dispose();
        if (this.button != null) this.button.dispose();
        if (this.buttonHighlighted != null) this.buttonHighlighted.dispose();
        if (this.buttonDisabled != null) this.buttonDisabled.dispose();
        if (this.slider != null) this.slider.dispose();
        if (this.sliderHighlighted != null) this.sliderHighlighted.dispose();
        if (this.sliderHandle != null) this.sliderHandle.dispose();
        if (this.sliderHandleHighlighted != null) this.sliderHandleHighlighted.dispose();
        if (this.textField != null) this.textField.dispose();
        if (this.textFieldHighlighted != null) this.textFieldHighlighted.dispose();
        if (this.menuBackground != null) this.menuBackground.dispose();
        if (this.menuBackgroundImage != null) this.menuBackgroundImage.dispose();
    }
}
