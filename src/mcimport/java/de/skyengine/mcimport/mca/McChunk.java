package de.skyengine.mcimport.mca;

import de.skyengine.mcimport.nbt.NbtCompound;

import java.util.List;

/**
 * Neutraler Minecraft-Chunk (16×16-Spalte): Chunk-Koordinaten, vollständige DataVersion
 * und die Block-Sections. {@code skippedSections} zählt Sections ohne {@code block_states}
 * (Licht-only — dokumentierte, normale Auslassung, kein Fehler).
 * {@code blockEntities} sind die ROHEN {@code block_entities}-Compounds (id, x/y/z,
 * Items, …) — Interpretation erst beim Import (M6), der Parser bleibt neutral.
 */
public record McChunk(int x, int z, int dataVersion, List<McSection> sections,
                      int skippedSections, List<NbtCompound> blockEntities) {}
