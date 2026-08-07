package de.skyengine.game.world.item;

/**
 * Tool-Material (MC-Werte): Abbau-Geschwindigkeit, Harvest-Level (welche Blöcke droppen)
 * und Haltbarkeit. Level: 0 = Holz/Gold, 1 = Stein/Kupfer, 2 = Eisen, 3 = Diamant, 4 = Netherite.
 */
public enum ToolTier {
    WOOD(2F, 0, 59, "wooden"),
    STONE(4F, 1, 131, "stone"),
    COPPER(5F, 1, 190, "copper"),
    IRON(6F, 2, 250, "iron"),
    GOLD(12F, 0, 32, "golden"),
    DIAMOND(8F, 3, 1561, "diamond"),
    NETHERITE(9F, 4, 2031, "netherite");

    private final float speed;
    private final int level;
    private final int durability;
    private final String prefix; // Item-ID-/Textur-Präfix (MC: "wooden_", "golden_")

    ToolTier(float speed, int level, int durability, String prefix) {
        this.speed = speed;
        this.level = level;
        this.durability = durability;
        this.prefix = prefix;
    }

    public float speed() {
        return speed;
    }

    public int level() {
        return level;
    }

    public int durability() {
        return durability;
    }

    public String prefix() {
        return prefix;
    }

    /** JSON-Name bzw. ID-Praefix -> Tier. */
    public static ToolTier byName(String name) {
        if (name == null) return null;
        for (ToolTier tier : values()) {
            if (tier.name().equalsIgnoreCase(name) || tier.prefix.equalsIgnoreCase(name)
                    || (tier == WOOD && "wood".equalsIgnoreCase(name))
                    || (tier == GOLD && "gold".equalsIgnoreCase(name))) return tier;
        }
        return null;
    }

    /** Harvest-Level aus dem JSON-Namen ({@code harvest_tier}): "wood"|"stone"|"copper"|"iron"|"diamond"|"netherite". */
    public static int levelByName(String name) {
        return switch (name) {
            case "wood" -> 0;
            case "stone", "copper" -> 1;
            case "iron" -> 2;
            case "diamond" -> 3;
            case "netherite" -> 4;
            default -> throw new IllegalArgumentException("Unbekanntes harvest_tier: " + name);
        };
    }
}
