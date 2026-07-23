package de.skyengine.mcimport;

import de.skyengine.mcimport.mca.McBlockState;
import de.skyengine.mcimport.mca.McChunk;
import de.skyengine.mcimport.mca.McChunkParser;
import de.skyengine.mcimport.mca.McRegionFile;
import de.skyengine.mcimport.mca.McSection;
import de.skyengine.mcimport.nbt.NbtReader;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * M4-Verifikations-Endpunkt: liest eine Minecraft-Welt (1.18+) und gibt Detail-Dump +
 * Histogramme aus — noch KEIN Mapping, kein Schreiben. Standalone-main, komplett
 * engine-frei (System.out, Muster GeneratorMapExporter).
 *
 * <p>Aufruf: {@code ./gradlew mcAnalyze --args="<weltPfad> [maxChunks=500]"}
 */
public final class McWorldAnalyzer {

    private static final Pattern REGION_NAME = Pattern.compile("r\\.(-?\\d+)\\.(-?\\d+)\\.mca");
    private static final int HISTOGRAM_TOP = 30;

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.out.println("Aufruf: McWorldAnalyzer <weltPfad> [maxChunks]");
            System.exit(1);
        }
        File world = new File(args[0]);
        int maxChunks = args.length >= 2 ? Integer.parseInt(args[1]) : 500;

        File regionDir = new File(world, "region");
        File[] regionFiles = regionDir.listFiles((dir, name) -> REGION_NAME.matcher(name).matches());
        if (regionFiles == null || regionFiles.length == 0) {
            System.out.println("Keine region/*.mca gefunden unter: " + regionDir.getAbsolutePath()
                    + " — keine (moderne) Java-Edition-Welt?");
            System.exit(1);
        }
        java.util.Arrays.sort(regionFiles, Comparator.comparing(File::getName));

        Map<String, Long> byBlockType = new HashMap<>();
        Map<String, Long> byBlockState = new HashMap<>();
        int chunksRead = 0, chunksNotFull = 0, chunkErrors = 0;
        long lightOnlySections = 0;
        int worldDataVersion = -1;
        boolean detailPrinted = false;
        List<String> firstErrors = new ArrayList<>();

        long start = System.currentTimeMillis();
        outer:
        for (File file : regionFiles) {
            Matcher m = REGION_NAME.matcher(file.getName());
            if (!m.matches()) continue;
            try (McRegionFile region = new McRegionFile(file)) {
                for (int lz = 0; lz < 32; lz++) {
                    for (int lx = 0; lx < 32; lx++) {
                        if (!region.has(lx, lz)) continue;
                        try {
                            byte[] nbtBytes = region.readChunkData(lx, lz);
                            McChunk chunk = McChunkParser.parse(NbtReader.read(
                                    new DataInputStream(new ByteArrayInputStream(nbtBytes))));
                            if (chunk == null) {
                                chunksNotFull++;
                                continue;
                            }
                            chunksRead++;
                            lightOnlySections += chunk.skippedSections();
                            if (worldDataVersion < 0) worldDataVersion = chunk.dataVersion();
                            if (!detailPrinted) {
                                printDetail(chunk, file.getName());
                                detailPrinted = true;
                            }
                            tally(chunk, byBlockType, byBlockState);
                            if (chunksRead >= maxChunks) break outer;
                        } catch (Exception e) {
                            chunkErrors++;
                            if (firstErrors.size() < 3) {
                                firstErrors.add(file.getName() + " (" + lx + "," + lz + "): " + e.getMessage());
                            }
                        }
                    }
                }
            } catch (Exception e) {
                chunkErrors++;
                if (firstErrors.size() < 3) firstErrors.add(file.getName() + ": " + e.getMessage());
            }
        }
        long elapsed = System.currentTimeMillis() - start;

        printHistogram("Blocktyp-Histogramm (Top " + HISTOGRAM_TOP + ")", byBlockType);
        printHistogram("BlockState-Histogramm (Top " + HISTOGRAM_TOP + ")", byBlockState);

        System.out.println();
        System.out.println("=== Zusammenfassung ===");
        System.out.println("World DataVersion:        " + (worldDataVersion < 0 ? "unbekannt" : worldDataVersion));
        System.out.println("Anzahl Regionen:          " + regionFiles.length);
        System.out.println("Anzahl Chunks gelesen:    " + chunksRead);
        System.out.println("Anzahl Chunks übersprungen: " + (chunksNotFull + chunkErrors)
                + " (nicht-full: " + chunksNotFull + ", Fehler: " + chunkErrors + ")");
        System.out.println("Licht-only-Sections:      " + lightOnlySections);
        System.out.println("Verschiedene Blocktypen:  " + byBlockType.size());
        System.out.println("Verschiedene BlockStates: " + byBlockState.size());
        System.out.println("Dauer: " + elapsed + " ms");
        if (!firstErrors.isEmpty()) {
            System.out.println("Erste Fehler:");
            for (String error : firstErrors) System.out.println("  " + error);
        }
        System.exit(chunksRead > 0 ? 0 : 1);
    }

    /** Zählt die Blöcke des Chunks in beide Histogramme (Paletten-Zählung, kein Voll-Scan). */
    private static void tally(McChunk chunk, Map<String, Long> byType, Map<String, Long> byState) {
        for (McSection section : chunk.sections()) {
            int[] counts = new int[section.palette().size()];
            if (section.indices() == null) {
                counts[0] = McSection.VOLUME;
            } else {
                for (int index : section.indices()) counts[index]++;
            }
            for (int i = 0; i < counts.length; i++) {
                if (counts[i] == 0) continue;
                McBlockState state = section.palette().get(i);
                byType.merge(state.name(), (long) counts[i], Long::sum);
                byState.merge(state.toString(), (long) counts[i], Long::sum);
            }
        }
    }

    private static void printDetail(McChunk chunk, String regionFile) {
        System.out.println("=== Beispiel-Chunk (" + regionFile + ") ===");
        System.out.println("Chunk:       x=" + chunk.x() + " z=" + chunk.z());
        System.out.println("DataVersion: " + chunk.dataVersion());
        System.out.println("Sections:    " + chunk.sections().size()
                + " (+ " + chunk.skippedSections() + " Licht-only übersprungen)");
        for (McSection section : chunk.sections()) {
            System.out.println("  Y=" + String.format("%3d", section.y())
                    + "  Palette=" + section.palette().size()
                    + (section.indices() == null ? " (Single-Value: " + section.palette().get(0) + ")" : ""));
        }
        System.out.println();
    }

    private static void printHistogram(String title, Map<String, Long> histogram) {
        System.out.println("=== " + title + " ===");
        long total = histogram.values().stream().mapToLong(Long::longValue).sum();
        histogram.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(HISTOGRAM_TOP)
                .forEach(entry -> System.out.printf("%,14d  %s%n", entry.getValue(), entry.getKey()));
        System.out.printf("%,14d  (Summe über %d Einträge)%n%n", total, histogram.size());
    }

    private McWorldAnalyzer() {}
}
