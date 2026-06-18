package de.skyengine.game.world.block.model;

/**
 * Hilfen für datengetriebene Box-Geometrie in Minecraft-/Blockbench-Pixelkoordinaten
 * (0..16). Intern rechnet die Engine in lokalen 0..1-Koordinaten; {@link #px} konvertiert.
 *
 * <p>{@link ModelBox} ist das Gson-DTO einer einzelnen Box aus einer Block-JSON
 * ({@code from}/{@code to} in Pixeln). Dieses Fundament wird von Phase 3 (voller
 * models/blockstates-Parser) direkt weiterverwendet.
 */
public final class ModelElements {

    private ModelElements() {}

    /** Pixel (0..16) -> lokale Blockkoordinate (0..1). */
    public static double px(double pixel) {
        return pixel / 16.0;
    }

    /** Gson-DTO: eine Box in Pixelkoordinaten (from/to je [x,y,z], 0..16). */
    public static final class ModelBox {
        public int[] from;      // [x,y,z] in 0..16
        public int[] to;        // [x,y,z] in 0..16
        public String texture;  // optionaler Key in die textures-Map
        public Boolean cull;    // optionales Culling-Flag (Phase 3)

        public double x0() { return px(from[0]); }
        public double y0() { return px(from[1]); }
        public double z0() { return px(from[2]); }
        public double x1() { return px(to[0]); }
        public double y1() { return px(to[1]); }
        public double z1() { return px(to[2]); }
    }

    /**
     * Baut ein {@link BoxElement} aus einer {@link ModelBox} mit explizit vorgegebenen
     * Texturlayern und Cull-Indizes je Face (0=top,1=bottom,2=north,3=south,4=west,5=east;
     * Texturlayer {@link BakedQuad#NO_FACE} lässt das Face weg).
     */
    public static BoxElement toBoxElement(ModelBox def, int[] tex6, int[] cull6) {
        return new BoxElement(def.x0(), def.y0(), def.z0(), def.x1(), def.y1(), def.z1(), tex6, cull6);
    }
}
