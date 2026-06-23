package de.skyengine.graphics.gui;

import de.skyengine.core.file.FileHandle;
import de.skyengine.core.file.FileType;
import de.skyengine.graphics.texture.Texture;

/**
 * Lädt und hält die (MC-kompatiblen) GUI-Texturen als einzelne {@link Texture} (NEAREST, ohne Mipmaps).
 * Pfade entsprechen dem Minecraft-Layout unter {@code game/textures/gui/...}.
 */
public final class GuiTextures {

    public Texture chestBackground;
    public Texture hotbar;
    public Texture hotbarSelection;
    public Texture crosshair;

    public void init() {
        this.chestBackground = load("game/textures/gui/container/generic_54.png");
        this.hotbar = load("game/textures/gui/sprites/hud/hotbar.png");
        this.hotbarSelection = load("game/textures/gui/sprites/hud/hotbar_selection.png");
        this.crosshair = load("game/textures/gui/sprites/hud/crosshair.png");
    }

    private static Texture load(String path) {
        return new Texture(new FileHandle(path, FileType.RESOURCE), false);
    }

    public void dispose() {
        if (this.chestBackground != null) this.chestBackground.dispose();
        if (this.hotbar != null) this.hotbar.dispose();
        if (this.hotbarSelection != null) this.hotbarSelection.dispose();
        if (this.crosshair != null) this.crosshair.dispose();
    }
}
