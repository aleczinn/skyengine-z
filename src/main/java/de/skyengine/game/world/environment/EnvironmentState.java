package de.skyengine.game.world.environment;

import org.joml.Vector3f;

/** Pro Frame berechneter Zustand; das Update erzeugt keinen Garbage im Renderpfad. */
public final class EnvironmentState {

    public final Vector3f sunDirection = new Vector3f();
    public final Vector3f moonDirection = new Vector3f();
    public final Vector3f skyLightColor = new Vector3f();
    public final Vector3f fogColor = new Vector3f();
    public final Vector3f skyTint = new Vector3f(1F);

    public float skyIntensity;
    public float daylight;
    public float night;
    public float dayFraction;
    public float fogDensity;
    public float starIntensity;
    public float moonPhase;

    public void update(EnvironmentProfile profile, BiomeEnvironmentModifier biome, double dayTime) {
        DayNightCycle.sunDirection(dayTime, this.sunDirection);
        this.moonDirection.set(this.sunDirection).negate();
        this.dayFraction = DayNightCycle.dayFraction(dayTime);
        this.daylight = DayNightCycle.daylight(dayTime);
        this.night = DayNightCycle.night(dayTime);
        this.skyIntensity = DayNightCycle.skyIntensity(dayTime);

        float sunElevation = this.sunDirection.y;
        float twilight = (1F - smoothstep(0.02F, 0.40F, Math.abs(sunElevation)))
                * smoothstep(-0.16F, 0.02F, sunElevation);
        this.skyLightColor.set(
                mix(0.56F, 1F, this.daylight),
                mix(0.66F, 1F, this.daylight),
                mix(0.92F, 1F, this.daylight));
        float fogR = mix(profile.nightSkyR() * 1.5F, 0.72F, this.daylight);
        float fogG = mix(profile.nightSkyG() * 1.5F, 0.83F, this.daylight);
        float fogB = mix(profile.nightSkyB() * 1.5F, 0.94F, this.daylight);
        float warm = twilight * 0.52F;
        this.fogColor.set(mix(fogR, 0.96F, warm), mix(fogG, 0.40F, warm),
                mix(fogB, 0.12F, warm)).mul(biome.fogR(), biome.fogG(), biome.fogB());
        this.skyTint.set(biome.skyR(), biome.skyG(), biome.skyB());
        this.fogDensity = profile.fogDensity() * biome.fogDensityMultiplier();
        this.starIntensity = profile.starIntensity() * this.night;

        long day = (long) Math.floor(dayTime / DayNightCycle.DAY_LENGTH);
        this.moonPhase = (float) Math.floorMod(day, 8) / 8F;
    }

    private static float mix(float a, float b, float t) {
        return a + (b - a) * t;
    }

    private static float smoothstep(float edge0, float edge1, float value) {
        float t = Math.clamp((value - edge0) / (edge1 - edge0), 0F, 1F);
        return t * t * (3F - 2F * t);
    }
}
