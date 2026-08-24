package de.skyengine.game.world.particle;

import de.skyengine.game.world.block.BlockTextures;

/** Registriert die kleine Teilmenge des Vanilla-Partikelatlanten, die SkyEngine benutzt. */
public final class ParticleSprites {

    private static int[] generic, genericReverse, bubblePop, splash, explosion, leaves, paleOakLeaves;
    private static int bubble, dripHang, dripFall, dripLand, lava, flame;

    public static void bootstrap() {
        if (generic != null) return;
        generic = sequence("generic_", 8);
        genericReverse = reverse(generic);
        bubblePop = sequence("bubble_pop_", 5);
        splash = sequence("splash_", 4);
        explosion = sequence("explosion_", 16);
        leaves = sequence("leaf_", 12);
        paleOakLeaves = sequence("pale_oak_", 12);
        bubble = layer("bubble");
        dripHang = layer("drip_hang");
        dripFall = layer("drip_fall");
        dripLand = layer("drip_land");
        lava = layer("lava");
        flame = layer("flame");
    }

    public static int layer(ParticleType type, int age, int lifetime) {
        bootstrap();
        return switch (type) {
            case BUBBLE -> bubble;
            case BUBBLE_POP -> frame(bubblePop, age, lifetime);
            case SPLASH -> frame(splash, age, lifetime);
            case LAVA -> lava;
            case FLAME -> flame;
            case EXPLOSION -> frame(explosion, age, lifetime);
            case DUST, PORTAL_BURST, SMOKE, LARGE_SMOKE, POOF, FALLING_DUST -> frame(genericReverse, age, lifetime);
            case PORTAL -> generic[0];
            case DRIP_HANG -> dripHang;
            case DRIP_FALL -> dripFall;
            case DRIP_LAND -> dripLand;
            case UNDERWATER -> generic[0];
            default -> frame(generic, age, lifetime);
        };
    }

    public static int randomLeaf(boolean paleOak, int index) {
        bootstrap();
        int[] sequence = paleOak ? paleOakLeaves : leaves;
        return sequence[Math.floorMod(index, sequence.length)];
    }

    /** Minecraft waehlt beim Erzeugen eines Portal-Partikels genau ein festes Generic-Sprite. */
    public static int randomPortal(int index) {
        bootstrap();
        return generic[Math.floorMod(index, generic.length)];
    }

    private static int frame(int[] sequence, int age, int lifetime) {
        int index = Math.min(sequence.length - 1,
                age * sequence.length / Math.max(1, lifetime));
        return sequence[index];
    }

    private static int[] sequence(String prefix, int count) {
        int[] out = new int[count];
        for (int i = 0; i < count; i++) out[i] = layer(prefix + i);
        return out;
    }

    private static int[] reverse(int[] source) {
        int[] out = new int[source.length];
        for (int i = 0; i < source.length; i++) out[i] = source[source.length - 1 - i];
        return out;
    }

    private static int layer(String name) {
        return BlockTextures.layerOf("game/textures/particle/" + name + ".png");
    }

    private ParticleSprites() {
    }
}
