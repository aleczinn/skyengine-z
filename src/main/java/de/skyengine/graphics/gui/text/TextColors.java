package de.skyengine.graphics.gui.text;

import de.skyengine.graphics.color.Color4;

import java.util.Map;

/**
 * Benannte Farben für das {@link RichText}-Markup ({@code <red>…</>}). Werte wie in Minecraft
 * (die {@code Colors}-Konstanten sind CSS-Farben und auf dunklem GUI-Grund teils zu satt).
 */
public final class TextColors {

    /** Minecrafts Standard-Grau ({@code §7}), zugleich Grundfarbe für Beschreibungen. */
    public static final Color4 GRAY = new Color4(0xFFAAAAAA);

    private static final Map<String, Color4> BY_NAME = Map.ofEntries(
            Map.entry("black", new Color4(0xFF000000)),
            Map.entry("dark_blue", new Color4(0xFF0000AA)),
            Map.entry("dark_green", new Color4(0xFF00AA00)),
            Map.entry("dark_aqua", new Color4(0xFF00AAAA)),
            Map.entry("dark_red", new Color4(0xFFAA0000)),
            Map.entry("dark_purple", new Color4(0xFFAA00AA)),
            Map.entry("gold", new Color4(0xFFFFAA00)),
            Map.entry("gray", GRAY),
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

    /** Minecraft-Legacy-Farbe zu {@code §0..§f}; null bei keinem Farbcode. */
    public static Color4 parseLegacy(char code) {
        return switch (Character.toLowerCase(code)) {
            case '0' -> BY_NAME.get("black");
            case '1' -> BY_NAME.get("dark_blue");
            case '2' -> BY_NAME.get("dark_green");
            case '3' -> BY_NAME.get("dark_aqua");
            case '4' -> BY_NAME.get("dark_red");
            case '5' -> BY_NAME.get("dark_purple");
            case '6' -> BY_NAME.get("gold");
            case '7' -> BY_NAME.get("gray");
            case '8' -> BY_NAME.get("dark_gray");
            case '9' -> BY_NAME.get("blue");
            case 'a' -> BY_NAME.get("green");
            case 'b' -> BY_NAME.get("aqua");
            case 'c' -> BY_NAME.get("red");
            case 'd' -> BY_NAME.get("light_purple");
            case 'e' -> BY_NAME.get("yellow");
            case 'f' -> BY_NAME.get("white");
            default -> null;
        };
    }

    private TextColors() {}
}
