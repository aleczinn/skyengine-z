package de.skyengine.mcimport;

import de.skyengine.core.file.Files;
import de.skyengine.game.world.block.Blocks;
import de.skyengine.mcimport.mapping.BlockMapper;
import de.skyengine.mcimport.mca.McBlockState;
import de.skyengine.mcimport.mca.McChunk;
import de.skyengine.mcimport.mca.McChunkParser;
import de.skyengine.mcimport.mca.McRegionFile;
import de.skyengine.mcimport.mca.McSection;
import de.skyengine.mcimport.nbt.NbtReader;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.File;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * M5-Verifikations-Endpunkt: prüft die {@link BlockMapper}-Abdeckung gegen eine echte
 * Minecraft-Welt (1.18+) — Quote gemappt/ungemappt und der vollständige
 * <b>Unknown-Block-Report</b> (jede nicht gemappte ID mit Blockzahl). Kein Schreiben.
 *
 * <p>Aufruf: {@code ./gradlew mcMapReport --args="<weltPfad> [maxChunks]"}
 */
public final class McMappingReport {

    private static final Pattern REGION_NAME = Pattern.compile("r\\.(-?\\d+)\\.(-?\\d+)\\.mca");

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.out.println("Aufruf: McMappingReport <weltPfad> [maxChunks]");
            System.exit(1);
        }
        File world = new File(args[0]);
        int maxChunks = args.length >= 2 ? Integer.parseInt(args[1]) : Integer.MAX_VALUE;

        /* Engine-Registry headless laden (Muster GeneratorMapExporter) + Alias-Tabelle. */
        Blocks.bootstrap(new File(Files.RESOURCES_PATH, "game/blocks"));
        BlockMapper mapper = BlockMapper.loadDefault();

        File regionDir = new File(world, "region");
        File[] regionFiles = regionDir.listFiles((dir, name) -> REGION_NAME.matcher(name).matches());
        if (regionFiles == null || regionFiles.length == 0) {
            System.out.println("Keine region/*.mca gefunden unter: " + regionDir.getAbsolutePath());
            System.exit(1);
        }
        java.util.Arrays.sort(regionFiles, Comparator.comparing(File::getName));

        /* Pro MC-Block-ID: [0] = Blockzahl gesamt, [1] = davon gemappt. */
        Map<String, long[]> byId = new HashMap<>();
        Map<String, Long> unknownStates = new HashMap<>();
        int chunksRead = 0, chunksSkipped = 0;

        long start = System.currentTimeMillis();
        outer:
        for (File file : regionFiles) {
            try (McRegionFile region = new McRegionFile(file)) {
                for (int lz = 0; lz < 32; lz++) {
                    for (int lx = 0; lx < 32; lx++) {
                        if (!region.has(lx, lz)) continue;
                        try {
                            byte[] nbtBytes = region.readChunkData(lx, lz);
                            McChunk chunk = McChunkParser.parse(NbtReader.read(
                                    new DataInputStream(new ByteArrayInputStream(nbtBytes))));
                            if (chunk == null) {
                                chunksSkipped++;
                                continue;
                            }
                            chunksRead++;
                            tally(chunk, mapper, byId, unknownStates);
                            if (chunksRead >= maxChunks) break outer;
                        } catch (Exception e) {
                            chunksSkipped++;
                        }
                    }
                }
            } catch (Exception e) {
                chunksSkipped++;
            }
        }
        long elapsed = System.currentTimeMillis() - start;

        long totalBlocks = 0, mappedBlocks = 0;
        int mappedIds = 0, unknownIds = 0;
        for (long[] counts : byId.values()) {
            totalBlocks += counts[0];
            mappedBlocks += counts[1];
        }
        for (Map.Entry<String, long[]> entry : byId.entrySet()) {
            if (entry.getValue()[1] > 0) mappedIds++;
            else unknownIds++;
        }

        System.out.println("=== Mapping-Statistik ===");
        System.out.printf("Chunks gelesen: %,d (übersprungen: %,d), Dauer: %,d ms%n",
                chunksRead, chunksSkipped, elapsed);
        System.out.printf("Blöcke:  %,d von %,d gemappt (%.2f %%)%n",
                mappedBlocks, totalBlocks, totalBlocks == 0 ? 0 : 100.0 * mappedBlocks / totalBlocks);
        System.out.printf("IDs:     %d von %d gemappt (%d ungemappt -> Luft)%n%n",
                mappedIds, byId.size(), unknownIds);

        System.out.println("=== Unknown-Block-Report (VOLLSTÄNDIG, -> Luft) ===");
        byId.entrySet().stream()
                .filter(entry -> entry.getValue()[1] == 0)
                .sorted(Comparator.comparingLong((Map.Entry<String, long[]> e) -> e.getValue()[0]).reversed())
                .forEach(entry -> System.out.printf("%,14d  %s%n", entry.getValue()[0], entry.getKey()));

        if (!unknownStates.isEmpty()) {
            System.out.println();
            System.out.println("=== Ungemappte BlockStates (Detail, Top 50) ===");
            unknownStates.entrySet().stream()
                    .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                    .limit(50)
                    .forEach(entry -> System.out.printf("%,14d  %s%n", entry.getValue(), entry.getKey()));
        }
        System.exit(0);
    }

    private static void tally(McChunk chunk, BlockMapper mapper,
                              Map<String, long[]> byId, Map<String, Long> unknownStates) {
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
                boolean known = mapper.isKnown(state);
                long[] entry = byId.computeIfAbsent(state.name(), key -> new long[2]);
                entry[0] += counts[i];
                if (known) {
                    entry[1] += counts[i];
                } else {
                    unknownStates.merge(state.toString(), (long) counts[i], Long::sum);
                }
            }
        }
    }

    private McMappingReport() {}
}
