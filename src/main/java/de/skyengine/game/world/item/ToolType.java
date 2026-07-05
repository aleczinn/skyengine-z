package de.skyengine.game.world.item;

/** Tool-Klassen (Block-JSON-Feld {@code tool}): bestimmen, welches Tool einen Block effektiv abbaut. */
public enum ToolType {
    PICKAXE, AXE, SHOVEL, SWORD;

    /** JSON-Name -> Typ oder null bei unbekannt. */
    public static ToolType byName(String name) {
        for (ToolType type : values()) {
            if (type.name().equalsIgnoreCase(name)) return type;
        }
        return null;
    }
}
