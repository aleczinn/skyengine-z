package de.skyengine.game.world.redstone;

/** Geteilte Vanilla-Farbkurve fuer Redstone-Wire und seine DUST-Partikel. */
public final class RedstoneColors {

    private static final int[] COLORS = buildColors();

    public static int forPower(int power) {
        return COLORS[Math.clamp(power, 0, 15)];
    }

    private static int[] buildColors() {
        int[] colors = new int[16];
        for (int power = 0; power <= 15; power++) {
            float f = power / 15F;
            float r = f * 0.6F + (power > 0 ? 0.4F : 0.3F);
            float g = Math.clamp(f * f * 0.7F - 0.5F, 0F, 1F);
            float b = Math.clamp(f * f * 0.6F - 0.7F, 0F, 1F);
            colors[power] = (int) (r * 255F) << 16 | (int) (g * 255F) << 8 | (int) (b * 255F);
        }
        return colors;
    }

    private RedstoneColors() {
    }
}
