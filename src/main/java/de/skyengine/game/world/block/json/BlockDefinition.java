package de.skyengine.game.world.block.json;

import de.skyengine.game.world.block.model.ModelElements;

import java.util.HashMap;
import java.util.Map;

/** 1:1-Abbild einer Block-JSON-Datei (Gson-DTO). */
public class BlockDefinition {

    public String id;
    public String type = "cube";        // cube | cross | slab | stairs | fence | pane
    public String layer = "opaque";     // opaque | cutout | translucent
    public Boolean opaque;              // default: true wenn layer == opaque
    public Boolean solid;               // default: false bei cross, sonst true
    public boolean cull_same = false;   // Glas-an-Glas-Culling
    public Map<String, String> textures = new HashMap<>();

    /* Pfostenmaße in 0..16-Pixeln. Bei Connecting-Blöcken (fence/pane) liefert
       die x-Breite die Kollisions-Balkenbreite und ist daher Pflicht. */
    public ModelElements.ModelBox post;
}