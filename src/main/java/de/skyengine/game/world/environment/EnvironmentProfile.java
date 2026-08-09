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
            0.20F, 0.46F, 1.0F,
            0.035F, 0.72F,
            8.0F, 0.55F,
            0.012F, 0.025F, 0.055F,
            0.00072F, 0.58F);
}
