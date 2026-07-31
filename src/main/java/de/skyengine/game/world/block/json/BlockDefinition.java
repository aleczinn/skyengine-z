package de.skyengine.game.world.block.json;

import de.skyengine.game.world.block.model.ModelElements;

import java.util.HashMap;
import java.util.Map;

/** 1:1-Abbild einer Block-JSON-Datei (Gson-DTO). */
public class BlockDefinition {

    public String id;
    public String archetype;            // bevorzugt; fällt auf 'type' zurück (Rückwärtskompat.)
    public String type = "cube";        // cube | cross | slab | stairs | fence | pane
    public String layer = "opaque";     // opaque | cutout | translucent
    public Boolean opaque;              // default: true wenn layer == opaque
    public Boolean solid;               // default: false bei cross, sonst true
    public boolean cull_same = false;   // Glas-an-Glas-Culling
    public boolean no_lod_surface = false; // nie als LOD-Terrain-Oberfläche sampeln (Logs)
    public boolean leaves = false;      // Laub: bei LeavesQuality LOW cullt Laub-an-Laub
    /* Licht-Opazität 0..15: wie viel Himmelslicht eine Zelle schluckt. null = automatisch
       (opaker Vollblock 15, sonst 0). Explizit nur da, wo Licht DÄMPFEN soll — Wasser und
       Laub stehen auf 1: ohne das wäre Wasser für Licht Luft (kein Tiefengradient) und eine
       Baumkrone würde gar keinen Schatten werfen. */
    public Integer light_opacity;
    /* Eigenleuchten 0..15: wie hell der Block selbst strahlt (Fackel 14, Lava 15, wie MC).
       null/0 = leuchtet nicht. light_color ist die Farbe dieses Lichts als "#RRGGBB" — sie wird
       heute nur eingelesen und validiert und wirkt noch NICHT aufs Bild: Blocklicht ist in
       dieser Phase monochrom wie in Minecraft. Das Feld steht schon hier, damit die RGB-Phase
       nur noch Speicher und Shader anfassen muss, nicht die ganze Datenkette. */
    public Integer light_level;
    public String light_color;
    public boolean gravity = false;     // fällt nach unten (Sand, Kies) via GravityBehavior
    public boolean replaceable = false; // Platzieren in diese Zelle ersetzt den Block (Gras/Farn, wie MC — kein Drop)
    public boolean facing = false;      // horizontale Ausrichtung zum Spieler (Truhe, Ofen) via HorizontalFacingBehavior

    /* Explosion (TNT): explosion_power = Sprengkraft (null = nicht explosiv; MC-TNT = 4;
       ~60 Block Reichweite ≈ 45). explosion_fuse = Zünddauer in Ticks (default 80 = 4 s bei 20 TPS).
       Verdrahtet über ExplosionBehavior in ArchetypeBlockFactory. */
    public Float explosion_power;
    public Integer explosion_fuse;

    /* Vegetations-Tint (siehe Tints): "grass" | "foliage". tint_faces schränkt optional auf
       einzelne Faces ein (up/down/north/south/west/east); null = alle Quads (inkl. Cross).
       textures.overlay definiert zusätzlich getintete Seiten-Overlay-Quads (Grasblock). */
    public String tint;
    public String[] tint_faces;

    /* Platzierungs-/Stütz-Regeln (SupportBehavior): place_on = erlaubte Träger-Block-IDs
       (z.B. Cactus nur auf Sand/Cactus); place_on_full_top = Träger braucht eine volle
       tragende Oberseite (Vollblock, Top-Slab, kopfüber-Treppe). Beides gesetzt = beides. */
    public String[] place_on;
    public boolean place_on_full_top = false;

    /* Archetyp "attached": erlaubte Trägerflächen (floor/wall/ceiling); null = alle drei.
       Die Fackel lässt floor+wall zu, ein Hebel später zusätzlich ceiling. */
    public String[] attach_faces;

    /* Survival-Mining: hardness (null -> 0 = instant, -1 = unzerstörbar wie Bedrock),
       tool = effektive Tool-Klasse (pickaxe/axe/shovel/sword; null = Hand reicht, droppt immer),
       harvest_tier = Mindest-Material für Drops (wood/stone/copper/iron/diamond/netherite). */
    public Float hardness;
    public String tool;
    public String harvest_tier;

    /* Explosions-Widerstand (MC-Blast-Resistance). null = es gilt hardness — genau deshalb steht
       das Feld nur bei den Blöcken, bei denen Minecraft beide Werte auseinanderzieht (Stein 1.5/6,
       Obsidian 50/1200, End-Stone 3/9). Negativ = unzerstörbar; über den hardness-Fallback erbt
       Bedrock das automatisch. Gelesen nur vom Explosions-Raycast (Explosion.resistanceOf). */
    public Float resistance;

    /* Bewegung auf/in dem Block (MC-Semantik, gelesen von EntityPlayer.travelWalking):
       friction = Bodenreibung, Default 0.6 (Eis 0.98, Blaueis 0.989, Slimeblock 0.8) — höher =
       rutschiger. speed_factor = Faktor auf die Horizontalgeschwindigkeit, Default 1.0
       (Seelensand/Honig 0.4). jump_factor = Faktor auf die Sprungkraft, Default 1.0 (Honig 0.5). */
    public Float friction;
    public Float speed_factor;
    public Float jump_factor;

    /* Landung auf dem Block (MC-Semantik, ausgewertet in Entity.move bzw.
       EntityPlayer.updateFallDamage):
       bounciness = Anteil der Aufprallgeschwindigkeit, der umgekehrt wird. Default 0 = normales
       Landen; Slimeblock 1.0 (in MC federt auch das Bett mit 0.66). Nicht-Lebewesen federn
       zusätzlich gedämpft (Entity.bounceDamping), Sneaken unterdrückt den Bounce ganz.
       fall_damage_factor = Multiplikator auf den Fallschaden NACH Abzug der 3-Block-Schwelle
       (wie MCs calculateFallDamage). Default 1.0; Slimeblock 0 (immun), Honigblock 0.2. */
    public Float bounciness;
    public Float fall_damage_factor;

    /* Optionale Sound-Gruppe (stone/wood/gravel/grass/sand/snow/cloth/glass/slime/honey);
       null = Ableitung aus tool/archetype (siehe BlockSoundGroup.resolve). */
    public String sound;

    /* Optionaler Auf-/Zu-Sound (wood_door/iron_door/chest) für Tür und Truhe — eigenes Konzept
       neben "sound" (siehe BlockOpenSound.resolve). null = der Block hat keinen. */
    public String open_sound;

    /* Fluid (archetype "fluid"): max. Levelwert, Level-Verlust pro Block und Tick-Takt des
       Flusses. null -> Default je nach Wasser/Lava. textures.still/flow liefern die Sprites. */
    public Integer fluid_spread;
    public Integer fluid_tick;
    public Integer drop_off;

    public Map<String, String> textures = new HashMap<>();

    /* Modell-"Rumpf": der Name des Geometrie-Modells, in das die textures-Map oben eingesetzt
       wird (z.B. "block/cube_all"). Daraus entsteht beim Laden ein Modell namens block/<id> —
       genau der Name, den variants/multipart und der Auto-Default ohnehin erwarten. Damit
       erübrigt sich eine eigene models/-Datei, die nur parent + textures wiederholt. */
    public String model;

    /* Mehrere Rümpfe je Block: Suffix -> Rumpf, erzeugt block/<id><suffix>. Treppen brauchen
       "" / "_inner" / "_outer", Zäune "_post" / "_side" / "_inventory". "model" ist die
       Kurzform für den Suffix "". */
    public Map<String, String> models;

    /* Pfostenmaße in 0..16-Pixeln. Bei Connecting-Blöcken (fence/pane) liefert
       die x-Breite die Kollisions-Balkenbreite und ist daher Pflicht. */
    public ModelElements.ModelBox post;

    /* Optionaler BlockEntityType-Identifier (z.B. "skyengine:furnace") für „lebende" Blöcke. */
    public String block_entity;

    /* Selbst deklarierte Properties (Name -> Werte + Default). Kommen NACH den Properties des
       Archetyps in die Liste, damit bestehende Blöcke ihre State-Reihenfolge behalten.
       Achtung: jede Property multipliziert die State-Zahl des Blocks. */
    public Map<String, PropertyDef> properties;

    /** values: erlaubte Werte (Strings); default: Startwert (sonst gilt der erste Wert). */
    public static final class PropertyDef {
        public String[] values;
        @com.google.gson.annotations.SerializedName("default")
        public String defaultValue;   // 'default' ist ein Java-Schlüsselwort
    }

    /* Optionaler Kollisions-Override (getrennt vom Modell). Ersetzt die Archetyp-Default-Shape. */
    public CollisionDef collision;

    /** Kollisions-/Umriss-Boxen in 0..16-Pixeln. outline fällt auf boxes zurück. */
    public static final class CollisionDef {
        public ModelElements.ModelBox[] boxes;
        public ModelElements.ModelBox[] outline;
    }

    /* Mehrteiliger Block (Tür, hohe Pflanze, Bett) — siehe PartsBehavior. */
    public PartsDef parts;

    /**
     * property = Name der Teil-Property (z.B. "half"); offsets = Wert -> [dx,dy,dz], genau einer
     * muss [0,0,0] sein (der Ursprung); relative_to = "facing" rechnet die Offsets in
     * Blickrichtung (+z vorwärts, +x rechts) statt in Weltachsen.
     */
    public static final class PartsDef {
        public String property;
        public Map<String, int[]> offsets;
        public String relative_to;
    }

    /* Creative-Tab(s) dieses Blocks: ein String ODER eine Liste von Strings (ein Block darf in
       mehreren Tabs stehen, z.B. stone in building_blocks UND natural). Vererbbar über die
       Presets — Achtung: ein Kind ERSETZT den Preset-Wert vollständig (JsonMerge ersetzt
       Arrays/Primitiven, es hängt nicht an), wer den Preset-Tab behalten will, muss ihn mit
       auflisten. Fehlt das Feld, landet der Block im Sammel-Tab "misc" (mit Warnung). */
    public com.google.gson.JsonElement creative_tab;

    /* Optionales generisches Connection-System (Zäune, Pipes, Cables). */
    public ConnectionDef connection;

    /** axes: north/east/south/west/up/down; rule: "same_family" | "energy"; group: Verbindungs-Familie. */
    public static final class ConnectionDef {
        public String[] axes;
        public String rule;
        public String group;
    }
}