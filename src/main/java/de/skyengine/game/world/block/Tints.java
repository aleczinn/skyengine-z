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

    /** JSON-Name -> Tint; unbekannte Namen loggen eine Warnung und bleiben neutral (WHITE). */
    public static int byName(String name) {
        return switch (name) {
            case "grass" -> GRASS;
            case "foliage" -> FOLIAGE;
            default -> {
                LOGGER.warning("Unbekannter Tint-Name '" + name + "' — bleibt neutral");
                yield BakedQuad.WHITE;
            }
        };
    }

    private Tints() {}
}
