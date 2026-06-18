package de.skyengine.graphics.texture;

/**
 * Gson-DTO einer {@code <textur>.png.mcmeta}-Datei (Minecraft-Format). Die animierte
 * Quelltextur ist ein vertikaler Streifen aus N quadratischen Frames.
 */
public final class TextureAnimationMeta {

    public Animation animation;

    public static final class Animation {
        /** Ticks pro Frame (1 Tick = 50 ms). Default 1. */
        public Integer frametime;
        /** Optionale Frame-Reihenfolge (Indizes in den Streifen). Default: 0..N-1. */
        public int[] frames;
        /** Reserviert (Interpolation), aktuell ungenutzt. */
        public Boolean interpolate;
    }
}
