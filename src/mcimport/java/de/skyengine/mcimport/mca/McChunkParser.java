package de.skyengine.mcimport.mca;

import de.skyengine.mcimport.nbt.NbtCompound;
import de.skyengine.mcimport.nbt.NbtList;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Parst einen 1.18+-Chunk (NBT-AST) in die neutrale {@link McChunk}-Struktur.
 * Bewusst komplett zustandslos (nur statische Methoden, keine Felder) — später
 * problemlos parallel über mehrere Regionen nutzbar.
 *
 * <p>Strikt validierend: fehlende Pflichtfelder, Paletten-Indizes außerhalb, falsche
 * data-Längen → {@link IOException}. Dokumentierte Ausnahmen (kein Fehler):
 * nicht-{@code full}-Chunks liefern null (unfertige Weltrand-Chunks), Sections ohne
 * {@code block_states} (Licht-only) werden gezählt übersprungen.
 */
public final class McChunkParser {

    /** 1.18 (21w43a-Release-Format: sections/block_states auf Root-Ebene). */
    public static final int MIN_DATA_VERSION = 2860;

    private static final int SECTION_VOLUME = McSection.VOLUME;

    /**
     * @return der geparste Chunk oder null, wenn der Chunk nicht fertig generiert ist
     *         (Status != full — normaler Weltrand, vom Aufrufer gezählt)
     */
    public static McChunk parse(NbtCompound root) throws IOException {
        int dataVersion = root.requireInt("DataVersion");
        if (dataVersion < MIN_DATA_VERSION) {
            throw new IOException("DataVersion " + dataVersion + " — nur 1.18+ (>= "
                    + MIN_DATA_VERSION + ") unterstützt");
        }

        /* 1.18..1.19 schreiben "full", neuere "minecraft:full" — beide akzeptieren. */
        String status = root.getString("Status", "");
        if (!status.equals("minecraft:full") && !status.equals("full")) return null;

        int chunkX = root.requireInt("xPos");
        int chunkZ = root.requireInt("zPos");

        NbtList sectionList = root.requireList("sections");
        List<McSection> sections = new ArrayList<>(sectionList.size());
        int skipped = 0;
        for (int i = 0; i < sectionList.size(); i++) {
            NbtCompound section = sectionList.compoundAt(i);
            NbtCompound blockStates = section.getCompound("block_states");
            if (blockStates == null) {
                skipped++; // Licht-only-Section (nur sky/block light) — dokumentierte Auslassung
                continue;
            }
            sections.add(parseSection(section.requireInt("Y"), blockStates));
        }
        return new McChunk(chunkX, chunkZ, dataVersion, sections, skipped);
    }

    private static McSection parseSection(int y, NbtCompound blockStates) throws IOException {
        NbtList paletteList = blockStates.requireList("palette");
        if (paletteList.size() < 1) {
            throw new IOException("Leere Section-Palette (Y=" + y + ")");
        }
        List<McBlockState> palette = new ArrayList<>(paletteList.size());
        for (int i = 0; i < paletteList.size(); i++) {
            NbtCompound entry = paletteList.compoundAt(i);
            String name = entry.requireString("Name");
            NbtCompound props = entry.getCompound("Properties");
            Map<String, String> properties;
            if (props == null || props.size() == 0) {
                properties = Map.of();
            } else {
                properties = new LinkedHashMap<>();
                for (String key : props.keys()) {
                    properties.put(key, props.getString(key, ""));
                }
            }
            palette.add(new McBlockState(name, properties));
        }

        long[] data = blockStates.getLongArray("data");
        if (data == null) {
            /* Vanilla lässt data bei Paletten-Größe 1 weg (Single-Value). */
            if (palette.size() != 1) {
                throw new IOException("Section Y=" + y + ": data fehlt bei Paletten-Größe "
                        + palette.size());
            }
            return new McSection(y, palette, null);
        }

        /* Vanilla-1.16+-Packung: Eintraege ueberspannen KEINE Long-Grenzen
           (bits = max(4, ceil(log2(n))), 64/bits Eintraege pro Long, Rest = Padding). */
        int bits = Math.max(4, 32 - Integer.numberOfLeadingZeros(palette.size() - 1));
        int perLong = 64 / bits;
        int expectedLongs = (SECTION_VOLUME + perLong - 1) / perLong;
        if (data.length != expectedLongs) {
            throw new IOException("Section Y=" + y + ": data-Länge " + data.length + " statt "
                    + expectedLongs + " (Palette " + palette.size() + ", " + bits + " Bit)");
        }

        int[] indices = new int[SECTION_VOLUME];
        long mask = (1L << bits) - 1;
        for (int i = 0; i < SECTION_VOLUME; i++) {
            long word = data[i / perLong];
            int index = (int) ((word >>> ((i % perLong) * bits)) & mask);
            if (index >= palette.size()) {
                throw new IOException("Section Y=" + y + ": Paletten-Index " + index
                        + " außerhalb (Palette " + palette.size() + ")");
            }
            indices[i] = index;
        }
        return new McSection(y, palette, indices);
    }

    private McChunkParser() {}
}
