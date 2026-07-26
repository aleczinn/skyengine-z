package de.skyengine.game.world.item.json;

/** 1:1-Abbild einer Item-JSON-Datei (Gson-DTO). */
public class ItemDefinition {

    public String id;

    /** Maximale Stapelgröße; null = Item-Default (64). */
    public Integer max_stack;

    /** Pfad des flachen Sprites. Pflicht — ohne Textur wäre das Item unsichtbar. */
    public String texture;

    /** Essbar (MC-Werte): nutrition in Halb-Icons, saturation als unsichtbare Sättigung. */
    public FoodDef food;

    /** nutrition/saturation wie in {@link de.skyengine.game.world.item.FoodItem}. */
    public static final class FoodDef {
        public int nutrition;
        public float saturation;
    }
}
