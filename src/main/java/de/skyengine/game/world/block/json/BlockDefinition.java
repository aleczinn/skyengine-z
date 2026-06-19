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
    public boolean gravity = false;     // fällt nach unten (Sand, Kies) via GravityBehavior
    public boolean facing = false;      // horizontale Ausrichtung zum Spieler (Truhe, Ofen) via HorizontalFacingBehavior
    public Map<String, String> textures = new HashMap<>();

    /* Pfostenmaße in 0..16-Pixeln. Bei Connecting-Blöcken (fence/pane) liefert
       die x-Breite die Kollisions-Balkenbreite und ist daher Pflicht. */
    public ModelElements.ModelBox post;

    /* Optionaler BlockEntityType-Identifier (z.B. "skyengine:furnace") für „lebende" Blöcke. */
    public String block_entity;

    /* Optionaler Kollisions-Override (getrennt vom Modell). Ersetzt die Archetyp-Default-Shape. */
    public CollisionDef collision;

    /** Kollisions-/Umriss-Boxen in 0..16-Pixeln. outline fällt auf boxes zurück. */
    public static final class CollisionDef {
        public ModelElements.ModelBox[] boxes;
        public ModelElements.ModelBox[] outline;
    }

    /* Optionales generisches Connection-System (Zäune, Pipes, Cables). */
    public ConnectionDef connection;

    /** axes: north/east/south/west/up/down; rule: "same_family" | "energy"; group: Verbindungs-Familie. */
    public static final class ConnectionDef {
        public String[] axes;
        public String rule;
        public String group;
    }
}