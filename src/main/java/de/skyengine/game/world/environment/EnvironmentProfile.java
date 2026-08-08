package de.skyengine.game.world.environment;

/**
 * Unveränderliche Umgebungsparameter einer Dimension. Biome verändern nur die dafür
 * vorgesehenen Tints/Multiplikatoren; spätere Dimensionen können das Profil austauschen.
 */
public record EnvironmentProfile(
        float rayleighR, float rayleighG, float rayleighB,
        float mieStrength, float mieG,
        float sunIntensity, float moonIntensity,
        float nightSkyR, float nightSkyG, float nightSkyB,
        float fogDensity, float starIntensity) {

    public static final EnvironmentProfile OVERWORLD = new EnvironmentProfile(
            0.22F, 0.48F, 1.0F,
            0.085F, 0.76F,
            16.0F, 0.55F,
            0.004F, 0.007F, 0.018F,
            0.00065F, 0.85F);
}
