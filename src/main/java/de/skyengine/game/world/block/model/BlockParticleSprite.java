package de.skyengine.game.world.block.model;

/** Vorgebackene Textur- und Tintdaten für Terrain-Partikel eines Blockstates. */
public record BlockParticleSprite(int textureLayer, int tint, int tintType) {

    public static final BlockParticleSprite MISSING = new BlockParticleSprite(
            -1, BakedQuad.WHITE, BakedQuad.TINT_NONE);

    public boolean isPresent() {
        return this.textureLayer >= 0;
    }
}
