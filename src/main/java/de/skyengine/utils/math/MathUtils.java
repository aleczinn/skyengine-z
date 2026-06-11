package de.skyengine.utils.math;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.concurrent.ThreadLocalRandom;

public class MathUtils {

    public static ThreadLocalRandom RANDOM = ThreadLocalRandom.current();

    public static int random(int range) {
        return RANDOM.nextInt(range + 1);
    }

    public static float randomAsFloat() {
        return RANDOM.nextFloat();
    }

    public static int clamp(int value, int min, int max) {
        if (min > max) throw new IllegalArgumentException("min value is higher than max value");
        return value > max ? max : (Math.max(value, min));
    }

    public static double clamp(double value, double min, double max) {
        if (min > max) throw new IllegalArgumentException("min value is higher than max value");
        return value > max ? max : (Math.max(value, min));
    }

    public static float clamp(float value, float min, float max) {
        if (min > max) throw new IllegalArgumentException("min value is higher than max value");
        return value > max ? max : (Math.max(value, min));
    }

    public static double interpolate(double value, double lastValue, double alpha) {
        return value * alpha + lastValue * (1.0D - alpha);
    }

    public static float interpolate(float value, float lastValue, float alpha) {
        return value * alpha + lastValue * (1.0F - alpha);
    }

    public static float round(float value, int n) {
        BigDecimal b = new BigDecimal(value);
        return b.setScale(n, RoundingMode.HALF_UP).floatValue();
    }
}
