package de.skyengine.game.world.block.model;

import de.skyengine.game.world.chunk.ChunkMesher;

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

    /**
     * Wie {@link #px}, aber fuer Kanten von Modell-Elementen — mit Schutz gegen kollabierende
     * Mini-Offsets.
     *
     * <p>Minecraft-Modelle trennen koplanare Flaechen mit Werten wie {@code 0.001} bzw.
     * {@code 15.999} px. Das sind 1/16000 Block und damit weit unter der Aufloesung des
     * u16-Fixed-Points im Mesher (1/1024 Block, {@code ChunkMesher.POS_SCALE}): der Wert faellt
     * beim Packen auf die Blockgrenze zurueck, die Flaeche waere also wieder exakt koplanar zum
     * Nachbarblock und sprenkelt gegen ihn. Deshalb wird ein Wert, der auf eine Blockgrenze
     * rundet, ohne exakt darauf zu liegen, um genau EINEN Quantisierungsschritt von der Grenze
     * weggeschoben (0.001 -> 1/1024, 15.999 -> 1023/1024). Damit lassen sich Vanilla-Modelle
     * unveraendert uebernehmen.
     *
     * <p>Alles andere bleibt unangetastet: exakte Blockgrenzen (0/16), normale Rasterwerte wie
     * 7/16 und auch Halbpixel wie 3.5 runden nicht auf eine Blockgrenze.
     */
    public static double pxEdge(double pixel) {
        double block = pixel / 16.0;
        double scaled = block * ChunkMesher.POS_SCALE;
        long fixed = Math.round(scaled);
        /* Nicht an einer Blockgrenze, oder exakt darauf -> nichts zu tun. */
        if (fixed % (long) ChunkMesher.POS_SCALE != 0 || scaled == fixed) return block;
        return (fixed + (scaled > fixed ? 1 : -1)) / ChunkMesher.POS_SCALE;
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
