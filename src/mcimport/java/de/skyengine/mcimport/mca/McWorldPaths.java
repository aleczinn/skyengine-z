package de.skyengine.mcimport.mca;

import java.io.File;

/**
 * Findet den Block-Region-Ordner des Overworlds einer Java-Edition-Welt:
 * <ol>
 *   <li>Standard: {@code <welt>/region} (Vanilla),</li>
 *   <li>Fallback: {@code <welt>/dimensions/minecraft/overworld/region} — Layout mancher
 *       Map-Exporte/Tool-Konvertierungen.</li>
 * </ol>
 * Bewusst NUR der {@code region}-Ordner (Block-Chunks) — die {@code entities}/{@code poi}-
 * Ordner daneben enthalten ebenfalls .mca-Dateien, aber KEINE Block-Daten.
 */
public final class McWorldPaths {

    /** Region-Ordner mit mindestens einer .mca-Datei oder null. */
    public static File overworldRegionDir(File world) {
        File standard = new File(world, "region");
        if (hasMcaFiles(standard)) return standard;
        File dimensions = new File(world, "dimensions/minecraft/overworld/region");
        if (hasMcaFiles(dimensions)) return dimensions;
        return null;
    }

    private static boolean hasMcaFiles(File dir) {
        File[] files = dir.listFiles((d, name) -> name.endsWith(".mca"));
        return files != null && files.length > 0;
    }

    private McWorldPaths() {}
}
