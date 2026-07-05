package de.skyengine.game.world.block;

import de.skyengine.game.world.block.model.BakedQuad;
import de.skyengine.utils.logging.LogManager;
import de.skyengine.utils.logging.Logger;

/**
 * Vegetations-Tints (0xRRGGBB, multiplikativ wie {@link BakedQuad#tint()}).
 * Aktuell feste Platzhalter-Werte (Minecraft-Plains); später soll der Wert vom
 * Biome abhängen. Die Block-JSON referenziert sie über {@code "tint": "grass"|"foliage"}.
 */
public final class Tints {

    private static final Logger LOGGER = LogManager.getLogger(Tints.class.getName());

    public static final int GRASS = 0x91BD59;
    public static final int FOLIAGE = 0x77AB2F;
    /* MC-Festfarben (biome-unabhängig, wie Vanilla): Birke, Fichte, Mangrove. */
    public static final int FOLIAGE_BIRCH = 0x80A755;
    public static final int FOLIAGE_SPRUCE = 0x619961;
    public static final int FOLIAGE_MANGROVE = 0x92BD59;

    /**
     * JSON-Name -> Biome-Tint-Typ ({@link BakedQuad#TINT_GRASS}/{@link BakedQuad#TINT_FOLIAGE}).
     * Nur "grass" und "foliage" sind biome-abhängig; Festfarben (Birke, Fichte, ...) bleiben NONE.
     */
    public static int typeByName(String name) {
        return switch (name) {
            case "grass" -> BakedQuad.TINT_GRASS;
            case "foliage" -> BakedQuad.TINT_FOLIAGE;
            default -> BakedQuad.TINT_NONE;
        };
    }

    /** JSON-Name -> Tint; unbekannte Namen loggen eine Warnung und bleiben neutral (WHITE). */
    public static int byName(String name) {
        return switch (name) {
            case "grass" -> GRASS;
            case "foliage" -> FOLIAGE;
            case "foliage_birch" -> FOLIAGE_BIRCH;
            case "foliage_spruce" -> FOLIAGE_SPRUCE;
            case "foliage_mangrove" -> FOLIAGE_MANGROVE;
            default -> {
                LOGGER.warning("Unbekannter Tint-Name '" + name + "' — bleibt neutral");
                yield BakedQuad.WHITE;
            }
        };
    }

    private Tints() {}
}
