package de.skyengine.game.world.item.json;

/** 1:1-Abbild einer Item-JSON-Datei (Gson-DTO). */
public class ItemDefinition {

    public String id;

    /** Fabrikname: item, food, bucket, tool, flint_and_steel, item_frame oder minecart. */
    public String archetype;

    /** Maximale Stapelgröße; null = Item-Default (64). */
    public Integer max_stack;

    /** Pfad des flachen Sprites. Pflicht — ohne Textur wäre das Item unsichtbar. */
    public String texture;

    /** Weitere Atlas-Layer, die der Archetyp fuer seine Weltgeometrie benoetigt. */
    public String[] additional_textures;

    /** Essbar (MC-Werte): nutrition in Halb-Icons, saturation als unsichtbare Sättigung. */
    public FoodDef food;
    public BucketDef bucket;
    public ToolDef tool;

    /* Block-Identifier, den ein Rechtsklick mit diesem Item platziert (Redstone-Staub:
       "skyengine:redstone_wire"). Der Block trägt dann "no_item": true — das Item hier
       übernimmt Platzieren und Pick-Block (Items.forBlock); Drops kommen aus Loot-Tabellen. */
    public String places_block;

    /* Creative-Tab(s): ein String ODER eine Liste von Strings. Vererbbar über die Presets; ein
       Kind ERSETZT den Preset-Wert vollständig. Fehlt das Feld, landet das Item im Sammel-Tab
       "misc" (mit Warnung). Siehe de.skyengine.game.world.item.CreativeTabs. */
    public com.google.gson.JsonElement creative_tab;

    /** Internes Debug-Item: nicht in Creative-Inventar, Suche oder /give sichtbar. */
    public boolean command_only;

    /** Optionales Rest-Item nach Verbrauch als Crafting-/Maschinenzutat. */
    public String crafting_remainder;

    /** nutrition/saturation wie in {@link de.skyengine.game.world.item.FoodItem}. */
    public static final class FoodDef {
        public int nutrition;
        public float saturation;
    }

    public static final class BucketDef {
        /** Fluid-Block-ID; null/leer kennzeichnet den leeren Eimer. */
        public String fluid;
    }

    public static final class ToolDef {
        public String type;
        public String tier;
    }
}
