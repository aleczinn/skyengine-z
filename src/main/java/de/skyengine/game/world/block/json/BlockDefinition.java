package de.skyengine.game.world.block.json;

import java.util.HashMap;
import java.util.Map;

/** 1:1-Abbild einer Block-JSON-Datei (Gson-DTO). */
public class BlockDefinition {

    public String id;
    public String type = "cube";        // cube | cross (Phase 2: slab, stairs, fence, pane, ...)
    public String layer = "opaque";     // opaque | cutout | translucent
    public Boolean opaque;              // default: true wenn layer == opaque
    public Boolean solid;               // default: false bei cross, sonst true
    public boolean cull_same = false;   // Glas-an-Glas-Culling
    public Map<String, String> textures = new HashMap<>();
}