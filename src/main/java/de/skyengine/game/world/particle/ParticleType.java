package de.skyengine.game.world.particle;

/** Kompakte, feste Physikdefinition der aktuell verwendeten Vanilla-Partikel. */
public enum ParticleType {
    BLOCK(0.04F, 0.98F, true, false, 0.10F, 18, 32),
    FALLING_DUST(0.003F, 0.98F, true, true, 0.12F, 30, 44),
    UNDERWATER(0F, 0.96F, false, true, 0.04F, 20, 20),
    BUBBLE(-0.004F, 0.85F, false, true, 0.05F, 24, 36),
    BUBBLE_POP(0F, 1F, false, true, 0.05F, 5, 5),
    SPLASH(0.04F, 0.98F, false, true, 0.08F, 12, 20),
    LAVA(0.03F, 0.999F, false, true, 0.10F, 16, 32),
    SMOKE(-0.004F, 0.96F, false, true, 0.10F, 20, 32),
    LARGE_SMOKE(-0.003F, 0.94F, false, true, 0.18F, 24, 40),
    FLAME(0F, 0.96F, false, true, 0.10F, 10, 18),
    EXPLOSION(0F, 1F, false, true, 0.50F, 16, 16),
    DRIP_HANG(0F, 1F, false, true, 0.04F, 40, 40),
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
