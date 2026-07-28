package de.skyengine.graphics.gui.text;

import de.skyengine.graphics.color.Color4;

import java.util.Map;

/**
 * Benannte Farben für das {@link RichText}-Markup ({@code <red>…</>}). Werte wie in Minecraft
 * (die {@code Colors}-Konstanten sind CSS-Farben und auf dunklem GUI-Grund teils zu satt).
 */
public final class TextColors {

    private static final Map<String, Color4> BY_NAME = Map.ofEntries(
            Map.entry("black", new Color4(0xFF000000)),
            Map.entry("dark_blue", new Color4(0xFF0000AA)),
            Map.entry("dark_green", new Color4(0xFF00AA00)),
            Map.entry("dark_aqua", new Color4(0xFF00AAAA)),
            Map.entry("dark_red", new Color4(0xFFAA0000)),
            Map.entry("dark_purple", new Color4(0xFFAA00AA)),
            Map.entry("gold", new Color4(0xFFFFAA00)),
            Map.entry("gray", new Color4(0xFFAAAAAA)),
            Map.entry("dark_gray", new Color4(0xFF555555)),
            Map.entry("blue", new Color4(0xFF5555FF)),
            Map.entry("green", new Color4(0xFF55FF55)),
            Map.entry("aqua", new Color4(0xFF55FFFF)),
            Map.entry("red", new Color4(0xFFFF5555)),
            Map.entry("light_purple", new Color4(0xFFFF55FF)),
            Map.entry("yellow", new Color4(0xFFFFFF55)),
            Map.entry("white", new Color4(0xFFFFFFFF)));

    /** Farbe zu einem Namen oder {@code #rrggbb}; null, wenn unbekannt. */
    public static Color4 parse(String name) {
        if (name == null || name.isEmpty()) return null;
        if (name.charAt(0) == '#') {
            if (name.length() != 7) return null;
            try {
                return new Color4(0xFF000000 | Integer.parseInt(name.substring(1), 16));
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return BY_NAME.get(name);
    }

    private TextColors() {}
}
