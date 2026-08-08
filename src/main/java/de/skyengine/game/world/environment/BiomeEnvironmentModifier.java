package de.skyengine.game.world.environment;

/** Leichte, kombinierbare Abweichung eines Bioms vom Dimensionsprofil. */
public record BiomeEnvironmentModifier(
        float skyR, float skyG, float skyB,
        float fogR, float fogG, float fogB,
        float fogDensityMultiplier) {

    public static final BiomeEnvironmentModifier DEFAULT = new BiomeEnvironmentModifier(
            1F, 1F, 1F, 1F, 1F, 1F, 1F);
}
