package de.skyengine.mcimport.mca;

import java.util.List;

/**
 * Neutrale 16³-Section: Vanilla-Y-Index (−4..19 bei 1.18+), Palette und entpackte
 * Palette-Indizes in YZX-Reihenfolge ({@code y*256 + z*16 + x}).
 * {@code indices == null} = Single-Value-Palette (alle 4096 Zellen = palette[0]).
 */
public record McSection(int y, List<McBlockState> palette, int[] indices) {

    public static final int VOLUME = 16 * 16 * 16;

    /** Palette-Index der Zelle (lokal 0..15 je Achse). */
    public int paletteIndex(int x, int y, int z) {
        return this.indices == null ? 0 : this.indices[(y << 8) | (z << 4) | x];
    }
}
