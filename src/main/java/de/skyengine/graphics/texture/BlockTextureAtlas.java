package de.skyengine.graphics.texture;

import de.skyengine.game.world.block.BlockTextures;

/**
 * Welt-unabhängiger Besitzer des Block-{@link TextureArray} + der {@link SpriteAnimations}:
 * wird EINMAL beim Boot (nach {@code Blocks.bootstrap}) gebaut und lebt bis zum Engine-Ende —
 * GUI/Item-Icons brauchen das Atlas schon im Hauptmenü, bevor je eine Welt existiert, und
 * Welt-Ein-/Austritte dürfen es nicht neu erzeugen (Layer-Indizes stecken in den gebackenen
 * Modellen). Vorher lag der Aufbau im ChunkRenderer.init (per-World).
 */
public final class BlockTextureAtlas {

    /** Muss zur bisherigen ChunkRenderer-Konstante passen (Block-Texturen sind 16×16). */
    public static final int TEXTURE_SIZE = 16;

    private TextureArray textures;
    private SpriteAnimations animations;
    private long lastAnimNanos;

    /** Render-Thread, nach {@code Blocks.bootstrap} (Layer-Reihenfolge kommt aus dem Model-Bake). */
    public void init() {
        String[] paths = BlockTextures.getOrderedPaths();
        this.animations = SpriteAnimations.build(paths, TEXTURE_SIZE);
        this.textures = new TextureArray(TEXTURE_SIZE, paths, this.animations.animatedLayers());
        this.animations.uploadInitial(this.textures);
        /* Mipmaps neu bauen, jetzt mit echten Fluid-Frame-0-Daten (animierte Layer waren beim
           ersten glGenerateMipmap noch leer → hätten in der Ferne transparente Mips). */
        this.textures.regenerateMipmaps();
        this.lastAnimNanos = System.nanoTime();
    }

    /** Animierte Sprites weiterdrehen (1×/Frame aus dem ChunkRenderer). */
    public void tick() {
        long now = System.nanoTime();
        this.animations.tick(this.textures, (now - this.lastAnimNanos) / 1.0e9);
        this.lastAnimNanos = now;
    }

    public TextureArray textures() {
        return this.textures;
    }

    public void dispose() {
        if (this.animations != null) this.animations.dispose();
        if (this.textures != null) this.textures.dispose();
    }
}
