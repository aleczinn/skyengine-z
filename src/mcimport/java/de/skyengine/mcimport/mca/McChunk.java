package de.skyengine.mcimport.mca;

import java.util.List;

/**
 * Neutraler Minecraft-Chunk (16×16-Spalte): Chunk-Koordinaten, vollständige DataVersion
 * und die Block-Sections. {@code skippedSections} zählt Sections ohne {@code block_states}
 * (Licht-only — dokumentierte, normale Auslassung, kein Fehler).
 */
public record McChunk(int x, int z, int dataVersion, List<McSection> sections, int skippedSections) {}
