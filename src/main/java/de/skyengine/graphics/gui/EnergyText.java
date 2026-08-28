package de.skyengine.graphics.gui;

import java.util.Locale;

public final class EnergyText {
    public static String format(long rf) {
        long value = Math.max(0, rf);
        if (value < 1_000) return value + " RF";
        if (value < 1_000_000) return compact(value / 1_000D) + " kRF";
        if (value < 1_000_000_000L) return compact(value / 1_000_000D) + " MRF";
        return compact(value / 1_000_000_000D) + " GRF";
    }

    private static String compact(double value) {
        return value >= 100 ? String.format(Locale.ROOT, "%.0f", value)
                : value >= 10 ? String.format(Locale.ROOT, "%.1f", value)
                : String.format(Locale.ROOT, "%.2f", value);
    }
    private EnergyText() {}
}
