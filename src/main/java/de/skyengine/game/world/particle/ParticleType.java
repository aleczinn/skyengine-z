package de.skyengine.game.world.particle;

/** Kompakte, feste Physikdefinition der aktuell verwendeten Vanilla-Partikel. */
public enum ParticleType {
    BLOCK(0.04F, 0.98F, true, false, 0.10F, 18, 32),
    FALLING_DUST(0.003F, 1F, true, true, 0.10F, 28, 144),
    UNDERWATER(0F, 1F, false, true, 0.04F, 16, 80),
    BUBBLE(-0.002F, 0.85F, false, true, 0.05F, 8, 40),
    BUBBLE_POP(0.008F, 1F, false, true, 0.05F, 4, 4),
    SPLASH(0.0016F, 0.98F, true, true, 0.08F, 8, 40),
    LAVA(0.03F, 0.999F, true, false, 0.10F, 16, 80),
    SMOKE(-0.004F, 0.96F, true, false, 0.10F, 8, 40),
    LARGE_SMOKE(-0.004F, 0.96F, true, false, 0.25F, 20, 100),
    POOF(-0.004F, 0.9F, true, false, 0.10F, 18, 82),
    DUST(0F, 0.96F, true, true, 0.10F, 8, 40),
    FLAME(0F, 0.96F, false, true, 0.10F, 12, 44),
    EXPLOSION_EMITTER(0F, 1F, false, false, 0F, 8, 8),
    EXPLOSION(0F, 1F, false, false, 2F, 6, 9),
    FALLING_LEAF(0F, 1F, true, true, 0.10F, 300, 300),
    DRIP_HANG(0.0012F, 0.02F, false, true, 0.004F, 40, 40),
    DRIP_FALL(0.06F, 0.98F, true, true, 0.04F, 40, 80),
    DRIP_LAND(0F, 1F, false, true, 0.08F, 12, 20);

    public final float gravity;
    public final float drag;
    public final boolean collision;
    public final boolean translucent;
    public final float size;
    public final int minLifetime;
    public final int maxLifetime;

    ParticleType(float gravity, float drag, boolean collision, boolean translucent,
                 float size, int minLifetime, int maxLifetime) {
        this.gravity = gravity;
        this.drag = drag;
        this.collision = collision;
        this.translucent = translucent;
        this.size = size;
        this.minLifetime = minLifetime;
        this.maxLifetime = maxLifetime;
    }
}
