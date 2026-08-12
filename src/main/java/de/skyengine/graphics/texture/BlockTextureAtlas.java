package de.skyengine.graphics.texture;

import de.skyengine.game.world.block.BlockTextures;

/** Owns the world-independent block texture array and its sprite animations. */
public final class BlockTextureAtlas {

    public static final int TEXTURE_SIZE = 16;

    private TextureArray textures;
    private SpriteAnimations animations;
    private long lastAnimNanos;

    /** Render thread, after the block registry and model texture order were initialized. */
    public void init() {
        String[] paths = BlockTextures.getOrderedPaths();
        this.animations = SpriteAnimations.build(paths, TEXTURE_SIZE);
        this.textures = new TextureArray(TEXTURE_SIZE, paths, this.animations.animatedLayers());
        // updateLayer uploads the initial frame and its complete Minecraft mip chain.
        this.animations.uploadInitial(this.textures);
        this.lastAnimNanos = System.nanoTime();
    }

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
