package de.skyengine.game.world.environment;

/**
 * Unveränderliche Umgebungsparameter einer Dimension. Biome verändern nur die dafür
 * vorgesehenen Tints/Multiplikatoren; spätere Dimensionen können das Profil austauschen.
 */
public record EnvironmentProfile(
        /* Multiplikatoren für Photons physikalische Atmosphärenkoeffizienten. */
        float rayleighR, float rayleighG, float rayleighB,
        float mieStrength, float mieG,
        float sunIntensity, float moonIntensity,
        float nightSkyR, float nightSkyG, float nightSkyB,
        float fogDensity, float starIntensity) {

    public static final EnvironmentProfile OVERWORLD = new EnvironmentProfile(
            1.0F, 1.0F, 1.0F,
            1.0F, 0.77F,
            1.0F, 1.0F,
            0.022F, 0.045F, 0.095F,
            0.00062F, 1.0F);
}
