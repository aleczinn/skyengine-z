package de.skyengine.game.world.environment;

import org.joml.Vector3f;

import java.util.Locale;

/** Reine Umrechnungen und Himmelsfunktionen fuer einen Minecraft-Tag mit 24.000 Ticks. */
public final class DayNightCycle {

    public static final int DAY_LENGTH = 24_000;
    public static final int TICKS_PER_HOUR = 1_000;
    /** Photons Default aus settings.glsl: {@code sunPathRotation = -35.0}. */
    private static final float SUN_PATH_ROTATION = (float) Math.toRadians(-35.0);
    private static final float SUN_PATH_COS = (float) Math.cos(SUN_PATH_ROTATION);
    private static final float SUN_PATH_SIN = (float) Math.sin(SUN_PATH_ROTATION);

    /** Tagesposition 0..24000, auch fuer negative/mehrtaegige Werte korrekt. */
    public static double wrappedTicks(double time) {
        double wrapped = time % DAY_LENGTH;
        return wrapped < 0 ? wrapped + DAY_LENGTH : wrapped;
    }

    /** 0 Ticks = 06:00, entsprechend der Minecraft-Konvention. */
    public static double clockToTicks(int hour, int minute) {
        int minutes = Math.floorMod(hour, 24) * 60 + Math.clamp(minute, 0, 59);
        return Math.floorMod(minutes - 6 * 60, 24 * 60) * (DAY_LENGTH / (24.0 * 60.0));
    }

    public static String formatClock(double time) {
        int totalMinutes = Math.floorMod((int) Math.floor(wrappedTicks(time) * 1440.0 / DAY_LENGTH) + 360, 1440);
        return String.format(Locale.ROOT, "%02d:%02d", totalMinutes / 60, totalMinutes % 60);
    }

    public static float dayFraction(double time) {
        return (float) (wrappedTicks(time) / DAY_LENGTH);
    }

    /** Richtung vom Beobachter zur Sonne; +X ist Osten, +Y oben. */
    public static Vector3f sunDirection(double time, Vector3f dest) {
        double angle = dayFraction(time) * Math.PI * 2.0;
        float pathHeight = (float) Math.sin(angle);
        return dest.set((float) Math.cos(angle),
                pathHeight * SUN_PATH_COS,
                pathHeight * SUN_PATH_SIN);
    }

    public static Vector3f moonDirection(double time, Vector3f dest) {
        return sunDirection(time, dest).negate();
    }

    public static float sunElevation(double time) {
        double angle = dayFraction(time) * Math.PI * 2.0;
        return (float) Math.sin(angle) * SUN_PATH_COS;
    }

    /** Sichtbarer Himmelslichtanteil: voller Tag, weiche Daemmerung, blaues Restlicht des Mondes. */
    public static float skyIntensity(double time) {
        return 0.16F + 0.84F * smoothstep(-0.10F, 0.22F, sunElevation(time));
    }

    public static float daylight(double time) {
        return smoothstep(-0.12F, 0.20F, sunElevation(time));
    }

    public static float night(double time) {
        return 1F - smoothstep(-0.18F, 0.05F, sunElevation(time));
    }

    public static float smoothstep(float edge0, float edge1, float value) {
        float t = Math.clamp((value - edge0) / (edge1 - edge0), 0F, 1F);
        return t * t * (3F - 2F * t);
    }

    private DayNightCycle() {
    }
}
