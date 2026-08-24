package de.skyengine.mcimport;

import de.skyengine.core.file.Files;
import de.skyengine.game.world.block.BlockPos;
import de.skyengine.game.world.block.Blocks;
import de.skyengine.game.world.block.Identifier;
import de.skyengine.game.world.block.Tints;
import de.skyengine.game.world.block.entity.BlockEntities;
import de.skyengine.game.world.block.entity.ChestBlockEntity;
import de.skyengine.game.world.block.entity.DataTag;
import de.skyengine.game.world.chunk.Chunk;
import de.skyengine.game.world.chunk.ChunkSection;
import de.skyengine.game.world.dimension.WorldgenRegistries;
import de.skyengine.game.world.item.Item;
import de.skyengine.game.world.item.ItemStack;
import de.skyengine.game.world.item.Items;
import de.skyengine.game.world.save.ChunkSerializer;
import de.skyengine.game.world.save.LevelData;
import de.skyengine.game.world.save.PlayerIO;
import de.skyengine.game.world.save.RegionFile;
import de.skyengine.game.world.save.WorldSaves;
import de.skyengine.graphics.gui.text.RichText;
import de.skyengine.mcimport.mapping.BlockMapper;
import de.skyengine.mcimport.mca.McBlockState;
import de.skyengine.mcimport.mca.McChunk;
import de.skyengine.mcimport.mca.McChunkParser;
import de.skyengine.mcimport.mca.McRegionFile;
import de.skyengine.mcimport.mca.McWorldPaths;
import de.skyengine.mcimport.mca.McSection;
import de.skyengine.mcimport.nbt.NbtCompound;
import de.skyengine.mcimport.nbt.NbtList;
import de.skyengine.mcimport.nbt.NbtReader;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * M6: einmalige Konvertierung einer Minecraft-Welt (1.18+) in eine SkyEngine-Welt
 * ({@code worldType=imported}, Void-Generator — alle Chunks kommen aus den Region-Dateien).
 *
 * <p>Geometrie: 2×2 MC-Chunks (16×16) → 1 Engine-Chunk (32×32); eine MC-Region
 * (32×32 MC-Chunks) entspricht damit EXAKT einer Engine-Region (16×16 Engine-Chunks) —
 * die Konvertierung läuft region-lokal ohne Fremdzugriffe. <b>Y-Offset +64</b>:
 * MC −64..319 → Engine 0..383 (128 Blöcke Baureserve nach oben); Werte außerhalb
 * werden gezählt übersprungen.
 *
 * <p>Verbindungs-Properties (Zäune/Panes) kommen 1:1 aus den MC-Blockstates mit —
 * kein Nach-Pass nötig. Truhen-Inhalte werden über Item-Identität übernommen
 * (unbekannte Items übersprungen + gezählt). Geschrieben wird durch DENSELBEN
 * {@code ChunkSerializer}/{@code RegionFile} wie im Spiel (Payload v2, Ticks leer,
 * Tints = konstante Plains-Grids, weil MC-Biome ≠ Engine-Biome).
 *
 * <p>Zwei Wege hinein: {@link #run(File, WorldSaves.WorldSave, Progress)} aus dem Spiel
 * (Weltauswahl → Importieren, läuft dort auf einem eigenen Thread) und {@link #main(String[])}
 * als Kommandozeilen-Werkzeug: {@code ./gradlew mcImport --args="<mcWeltPfad> <weltName>"}.
 */
public final class McWorldImporter {

    private static final Pattern REGION_NAME = Pattern.compile("r\\.(-?\\d+)\\.(-?\\d+)\\.mca");
    private static final int Y_OFFSET = 64;

    /**
     * Fortschritts-Senke: die GUI zeigt die Zeilen an, die CLI schreibt sie nach System.out.
     * Die Zeilen dürfen {@link RichText}-Markup enthalten ({@code <red>…</>}) — Empfänger ohne
     * Formatierung entfernen es mit {@code RichText.strip}.
     *
     * <p>Wird aus MEHREREN Worker-Threads gerufen, Implementierungen müssen thread-sicher sein.
     */
    public interface Progress {
        void log(String zeile);

        /** Nach jeder fertigen Region: {@code fertig} von {@code gesamt} Region-Dateien. */
        void step(int fertig, int gesamt);

        /** true = der Import soll an der nächsten Region-Grenze aufhören. */
        default boolean cancelled() {
            return false;
        }
    }

    /** Ergebnis eines Import-Laufs (die Zahlen der Abschluss-Summary). */
    public record Result(int chunksImported, int mcChunksMissing, int cellsOutOfRange,
                         int chestsImported, int itemsImported, int itemsSkipped,
                         int blockEntitiesSkipped, long elapsedMs, boolean spawnChunkMissing,
                         boolean cancelled) {}

    /**
     * Zähler EINER Region. Bewusst pro Aufgabe statt geteilter Atomics: die inneren Schleifen
     * zählen millionenfach, gemergt wird einmal am Ende.
     */
    private static final class Counters {
        private int chunksImported, mcChunksMissing, cellsOutOfRange, chestsImported;
        private int itemsImported, itemsSkipped, blockEntitiesSkipped;

        void add(Counters other) {
            this.chunksImported += other.chunksImported;
            this.mcChunksMissing += other.mcChunksMissing;
            this.cellsOutOfRange += other.cellsOutOfRange;
            this.chestsImported += other.chestsImported;
            this.itemsImported += other.itemsImported;
            this.itemsSkipped += other.itemsSkipped;
            this.blockEntitiesSkipped += other.blockEntitiesSkipped;
        }
    }

    /* Importierte Engine-Chunks (Chunk.key) — für die Spawn-Plausibilitätsprüfung. */
    private final java.util.Set<Long> importedChunks = ConcurrentHashMap.newKeySet();

    private final BlockMapper mapper;
    private final Progress progress;
    /* Konstante Plains-Tints (MC-Biome != Engine-Biome — bewusst simpel, im Payload). */
    private final int[] grassTints = new int[33 * 33];
    private final int[] foliageTints = new int[33 * 33];

    private McWorldImporter(BlockMapper mapper, Progress progress) {
        this.mapper = mapper;
        this.progress = progress;
        Arrays.fill(this.grassTints, Tints.GRASS);
        Arrays.fill(this.foliageTints, Tints.FOLIAGE);
    }

    /**
     * Konvertiert {@code mcWorld} in die bereits angelegte Ziel-Welt {@code target}
     * (siehe {@link #createTargetWorld(String)}).
     *
     * <p>Erwartet ein bereits gebootstrapptes Block-Registry ({@code Blocks.bootstrap}) —
     * im Spiel längst passiert, in der CLI macht es {@link #main}. Schreibt ausschließlich in
     * den Save-Ordner der Ziel-Welt (Region-Dateien + player.dat) und ist damit auch außerhalb
     * des Render-Threads aufrufbar, solange keine Welt geladen ist.
     *
     * @throws IOException wenn die Quelle keine Region-Dateien hat oder das Ziel nicht schreibbar ist
     */
    public static Result run(File mcWorld, WorldSaves.WorldSave target, Progress progress) throws IOException {
        File regionDir = McWorldPaths.overworldRegionDir(mcWorld);
        if (regionDir == null) {
            throw new IOException("Keine region/*.mca gefunden unter: " + mcWorld.getAbsolutePath()
                    + " (weder region/ noch dimensions/minecraft/overworld/region/)");
        }
        progress.log("Region-Quelle: " + regionDir.getAbsolutePath());
        File[] regionFiles = regionDir.listFiles((dir, name) -> REGION_NAME.matcher(name).matches());
        if (regionFiles == null || regionFiles.length == 0) {
            throw new IOException("Keine region/*.mca gefunden unter: " + regionDir.getAbsolutePath());
        }
        Arrays.sort(regionFiles, Comparator.comparing(File::getName));

        File targetRegionDir = new File(WorldSaves.dir(target.dirName()), "region");
        if (!targetRegionDir.exists() && !targetRegionDir.mkdirs()) {
            throw new IOException("Region-Verzeichnis nicht anlegbar: " + targetRegionDir);
        }

        McWorldImporter importer = new McWorldImporter(BlockMapper.loadDefault(), progress);
        long start = System.currentTimeMillis();
        Counters total = importer.convertAll(regionFiles, targetRegionDir);
        long elapsed = System.currentTimeMillis() - start;

        if (progress.cancelled()) {
            return new Result(total.chunksImported, total.mcChunksMissing, total.cellsOutOfRange,
                    total.chestsImported, total.itemsImported, total.itemsSkipped,
                    total.blockEntitiesSkipped, elapsed, false, true);
        }

        /* Spawn erst NACH der Konvertierung schreiben — dann können wir warnen, wenn er
           in einen nicht importierten Bereich zeigt (z.B. kaputte Export-Maps). */
        double[] spawn = importer.readSpawn(mcWorld);
        importer.writePlayerDat(target.dirName(), spawn);
        long spawnChunk = Chunk.key(((int) Math.floor(spawn[0])) >> ChunkSection.SHIFT,
                ((int) Math.floor(spawn[2])) >> ChunkSection.SHIFT);
        boolean spawnChunkMissing = !importer.importedChunks.contains(spawnChunk);
        if (spawnChunkMissing) {
            progress.log("<gold>WARNUNG: Der Spawn-Chunk wurde NICHT importiert — der Spieler");
            progress.log("         startet in leerem Gebiet. Spawn ggf. in der player.dat anpassen.</>");
        }

        return new Result(total.chunksImported, total.mcChunksMissing, total.cellsOutOfRange,
                total.chestsImported, total.itemsImported, total.itemsSkipped,
                total.blockEntitiesSkipped, elapsed, spawnChunkMissing, false);
    }

    /**
     * Alle Region-Dateien konvertieren — eine Aufgabe je Datei auf einem eigenen Worker-Pool.
     * Das ist zulässig, weil eine MC-Region genau einer Engine-Region entspricht: jede Aufgabe
     * arbeitet auf eigenen {@code Chunk}-/{@code RegionFile}-Instanzen, der geteilte
     * {@link BlockMapper} ist thread-sicher (ConcurrentHashMap-Caches), das Block-Registry ist
     * nach dem Bake read-only.
     */
    private Counters convertAll(File[] regionFiles, File targetRegionDir) {
        int threads = Math.min(regionFiles.length,
                Math.max(1, Runtime.getRuntime().availableProcessors() - 1));
        AtomicInteger threadIndex = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(threads, r -> {
            Thread thread = new Thread(r, "mc-import-worker-" + threadIndex.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
        AtomicInteger done = new AtomicInteger();
        List<Future<Counters>> futures = new ArrayList<>(regionFiles.length);
        for (File file : regionFiles) {
            Matcher m = REGION_NAME.matcher(file.getName());
            if (!m.matches()) continue;
            int rx = Integer.parseInt(m.group(1));
            int rz = Integer.parseInt(m.group(2));
            futures.add(pool.submit(() -> {
                Counters counters = new Counters();
                /* Abbruch: noch nicht gestartete Aufgaben fallen hier sofort durch. */
                if (this.progress.cancelled()) return counters;
                this.convertRegion(file, rx, rz, targetRegionDir, counters);
                this.progress.step(done.incrementAndGet(), regionFiles.length);
                return counters;
            }));
        }
        pool.shutdown();

        Counters total = new Counters();
        for (Future<Counters> future : futures) {
            try {
                total.add(future.get());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (ExecutionException e) {
                this.progress.log("<red>Region-Aufgabe fehlgeschlagen: " + e.getCause() + "</>");
            }
        }
        try {
            if (!pool.awaitTermination(30, TimeUnit.SECONDS)) {
                this.progress.log("<gold>Import-Worker haben nach 30 s nicht terminiert</>");
                pool.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            pool.shutdownNow();
        }
        return total;
    }

    /** Legt die Ziel-Welt an ({@code worldType=imported}) — identisch für CLI und GUI. */
    public static WorldSaves.WorldSave createTargetWorld(String worldName) {
        WorldSaves.WorldSave save = WorldSaves.create(worldName, 0);
        LevelData level = save.level();
        level.formatVersion = 2;
        level.worldType = "imported";
        level.generator = "minecraft_import";
        level.generatorVersion = 1;
        LevelData.DimensionData overworld = new LevelData.DimensionData();
        overworld.seed = level.seed;
        overworld.generator = WorldgenRegistries.MINECRAFT_IMPORT.toString();
        overworld.generatorVersion = 1;
        level.dimensions.put(WorldgenRegistries.OVERWORLD.toString(), overworld);
        WorldSaves.save(save);
        return save;
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.out.println("Aufruf: McWorldImporter <mcWeltPfad> <weltName>");
            System.exit(1);
        }
        File mcWorld = new File(args[0]);
        String worldName = args[1];

        Blocks.bootstrap(new File(Files.RESOURCES_PATH, "game/blocks"));
        WorldSaves.WorldSave save = createTargetWorld(worldName);

        Result result;
        try {
            result = run(mcWorld, save, new Progress() {
                @Override
                public void log(String zeile) {
                    /* Die Konsole kann kein Markup — Tags entfernen. */
                    System.out.println(RichText.strip(zeile));
                }

                @Override
                public void step(int fertig, int gesamt) {
                    /* Der CLI reichen die Region-Zeilen im Log als Fortschritt. */
                }
            });
        } catch (IOException e) {
            System.out.println(e.getMessage());
            System.exit(1);
            return;
        }

        System.out.println();
        System.out.println("=== Import fertig: " + save.dirName() + " ===");
        System.out.printf("Engine-Chunks geschrieben:  %,d (%,d ms)%n",
                result.chunksImported(), result.elapsedMs());
        System.out.printf("Fehlende MC-Chunks:         %,d (Quadrant bleibt Luft)%n", result.mcChunksMissing());
        System.out.printf("Zellen außerhalb 0..511:    %,d%n", result.cellsOutOfRange());
        System.out.printf("Truhen übernommen:          %,d (%,d Items, %,d Items unbekannt)%n",
                result.chestsImported(), result.itemsImported(), result.itemsSkipped());
        System.out.printf("Andere BlockEntities:       %,d übersprungen%n", result.blockEntitiesSkipped());
        System.exit(0);
    }

    /** Eine MC-Region (32×32 MC-Chunks) → exakt eine Engine-Region (16×16 Engine-Chunks). */
    private void convertRegion(File mcaFile, int rx, int rz, File targetRegionDir, Counters counters) {
        long start = System.currentTimeMillis();
        int written = 0;
        /* Batch-Modus (kein fsync je Chunk): spart 512 fsyncs pro Region-Datei. Ein Absturz
           mittendrin macht die Welt unbrauchbar — die wird dann ohnehin verworfen. */
        try (McRegionFile mcRegion = new McRegionFile(mcaFile);
             RegionFile target = new RegionFile(new File(targetRegionDir, "r." + rx + "." + rz + ".srg"), false)) {

            for (int ez = 0; ez < 16; ez++) {
                for (int ex = 0; ex < 16; ex++) {
                    int chunkX = rx * 16 + ex;
                    int chunkZ = rz * 16 + ez;
                    Chunk chunk = new Chunk(chunkX, chunkZ);
                    boolean any = false;

                    /* 2×2 MC-Chunks in die vier 16er-Quadranten des Engine-Chunks. */
                    for (int qz = 0; qz < 2; qz++) {
                        for (int qx = 0; qx < 2; qx++) {
                            McChunk mc = readMcChunk(mcRegion, ex * 2 + qx, ez * 2 + qz);
                            if (mc == null) {
                                counters.mcChunksMissing++;
                                continue;
                            }
                            any = true;
                            this.copyBlocks(mc, chunk, qx * 16, qz * 16, counters);
                            this.importBlockEntities(mc, chunk, counters);
                        }
                    }
                    if (!any) continue; // komplett ungenerierter Bereich -> gar nicht speichern

                    chunk.grassTintCorners = this.grassTints;
                    chunk.foliageTintCorners = this.foliageTints;
                    byte[] payload = ChunkSerializer.serialize(chunk, "minecraft_import", 1, true, null,
                            ChunkSerializer.snapshotBlockEntities(chunk));
                    target.write(chunkX & 15, chunkZ & 15, payload);
                    this.importedChunks.add(Chunk.key(chunkX, chunkZ));
                    counters.chunksImported++;
                    written++;
                }
            }
        } catch (Exception e) {
            this.progress.log("<red>" + mcaFile.getName() + ": FEHLER — " + e.getMessage() + "</>");
            return;
        }
        if (written == 0) {
            /* Nur Header, kein Chunk (z.B. MC-Region komplett prä-1.18) -> Datei weg. */
            new File(targetRegionDir, "r." + rx + "." + rz + ".srg").delete();
            return;
        }
        this.progress.log(mcaFile.getName() + " -> r." + rx + "." + rz + ".srg: "
                + written + " Chunks in " + (System.currentTimeMillis() - start) + " ms");
    }

    private static McChunk readMcChunk(McRegionFile region, int lx, int lz) {
        try {
            if (!region.has(lx, lz)) return null;
            byte[] nbtBytes = region.readChunkData(lx, lz);
            return McChunkParser.parse(NbtReader.read(
                    new DataInputStream(new ByteArrayInputStream(nbtBytes))));
        } catch (Exception e) {
            return null; // korrupter/alter Chunk -> Quadrant bleibt Luft (gezählt via missing)
        }
    }

    /** Kopiert die Blöcke eines MC-Chunks in den Quadranten (ox, oz) des Engine-Chunks (Y+64). */
    private void copyBlocks(McChunk mc, Chunk chunk, int ox, int oz, Counters counters) {
        for (McSection section : mc.sections()) {
            /* Palette EINMAL mappen, dann nur noch Indizes nachschlagen. */
            List<McBlockState> palette = section.palette();
            int[] mapped = new int[palette.size()];
            boolean anySolid = false;
            for (int i = 0; i < mapped.length; i++) {
                mapped[i] = this.mapper.map(palette.get(i));
                anySolid |= mapped[i] != 0;
            }
            if (!anySolid) continue; // reine Luft-Section (auch komplett ungemappte)

            int yBase = section.y() * 16 + Y_OFFSET;
            for (int cy = 0; cy < 16; cy++) {
                int worldY = yBase + cy;
                if (worldY < 0 || worldY >= Chunk.HEIGHT) {
                    counters.cellsOutOfRange += 256;
                    continue;
                }
                for (int cz = 0; cz < 16; cz++) {
                    for (int cx = 0; cx < 16; cx++) {
                        int stateId = mapped[section.paletteIndex(cx, cy, cz)];
                        if (stateId != 0) chunk.setBlock(ox + cx, worldY, oz + cz, stateId);
                    }
                }
            }
        }
    }

    /** Truhen samt Inhalt übernehmen (Item-Identität); andere BlockEntities zählen + skippen. */
    private void importBlockEntities(McChunk mc, Chunk chunk, Counters counters) {
        for (NbtCompound be : mc.blockEntities()) {
            String id = be.getString("id", "");
            if (!id.equals("minecraft:chest")) {
                counters.blockEntitiesSkipped++;
                continue;
            }
            int wx = be.getInt("x", 0);
            int wy = be.getInt("y", 0) + Y_OFFSET;
            int wz = be.getInt("z", 0);
            if (wy < 0 || wy >= Chunk.HEIGHT) continue;
            int lx = wx & ChunkSection.MASK;
            int lz = wz & ChunkSection.MASK;

            /* Nur wenn dort auch wirklich die (gemappte) Truhe steht. */
            int stateId = chunk.getBlock(lx, wy, lz);
            if (Blocks.getState(stateId).getBlock().getBlockEntityType() != BlockEntities.CHEST) {
                counters.blockEntitiesSkipped++;
                continue;
            }
            ChestBlockEntity chest = (ChestBlockEntity) BlockEntities.CHEST.create(
                    new BlockPos(wx, wy, wz), Blocks.getState(stateId));
            NbtList items = be.getList("Items");
            if (items != null) {
                for (int i = 0; i < items.size(); i++) {
                    try {
                        NbtCompound itemTag = items.compoundAt(i);
                        int slot = itemTag.getInt("Slot", -1);
                        if (slot < 0 || slot >= ChestBlockEntity.SLOTS) continue;
                        String mcItemId = itemTag.getString("id", "");
                        String path = mcItemId.startsWith("minecraft:")
                                ? mcItemId.substring("minecraft:".length()) : mcItemId;
                        Item item = Items.get(Identifier.of("skyengine:" + path));
                        if (item == null) {
                            counters.itemsSkipped++;
                            continue;
                        }
                        /* Stapelgröße: bis 1.20.4 „Count" (Byte), ab 1.20.5 „count" (Int) —
                           und ab da wird das Feld weggelassen, wenn die Anzahl 1 ist. */
                        int count = itemTag.getInt("count", itemTag.getInt("Count", 1));
                        chest.getInventory().set(slot, new ItemStack(item, Math.max(1, count)));
                        counters.itemsImported++;
                    } catch (Exception ignored) {
                        counters.itemsSkipped++;
                    }
                }
            }
            chunk.setBlockEntity(lx, wy, lz, chest);
            counters.chestsImported++;
        }
    }

    /**
     * Spawn aus der MC-level.dat (NBT, gzip): klassisch {@code Data.SpawnX/Y/Z} ODER das
     * neuere {@code Data.spawn}-Compound ({@code pos}-IntArray + yaw/pitch, ~1.21.6+).
     * Liefert Engine-Koordinaten (Y+64+1 Sicherheitsabstand) + yaw/pitch.
     */
    private double[] readSpawn(File mcWorld) {
        try (FileInputStream in = new FileInputStream(new File(mcWorld, "level.dat"))) {
            NbtCompound data = NbtReader.readAuto(in).requireCompound("Data");

            NbtCompound spawn = data.getCompound("spawn");
            if (spawn != null) {
                int[] pos = spawn.getIntArray("pos");
                if (pos != null && pos.length == 3) {
                    return new double[]{pos[0] + 0.5, pos[1] + Y_OFFSET + 1.0, pos[2] + 0.5,
                            spawn.getDouble("yaw", 0), spawn.getDouble("pitch", 0)};
                }
            }
            if (data.contains("SpawnX")) {
                return new double[]{data.getInt("SpawnX", 0) + 0.5,
                        data.getInt("SpawnY", 64) + Y_OFFSET + 1.0,
                        data.getInt("SpawnZ", 0) + 0.5, 0, 0};
            }
            this.progress.log("<gold>level.dat ohne Spawn-Angabe — Fallback 0/100/0</>");
        } catch (Exception e) {
            this.progress.log("<gold>level.dat nicht lesbar (" + e.getMessage()
                    + ") — Spawn-Fallback 0/100/0</>");
        }
        return new double[]{0.5, 100, 0.5, 0, 0};
    }

    /** Schreibt die player.dat (Creative + fliegend) an die Spawn-Position. */
    private void writePlayerDat(String dirName, double[] spawn) {
        DataTag tag = new DataTag();
        UUID uuid = UUID.randomUUID();
        tag.putLong("uuidMost", uuid.getMostSignificantBits());
        tag.putLong("uuidLeast", uuid.getLeastSignificantBits());
        tag.putDouble("x", spawn[0]);
        tag.putDouble("y", spawn[1]);
        tag.putDouble("z", spawn[2]);
        tag.putDouble("yaw", spawn[3]);
        tag.putDouble("pitch", spawn[4]);
        tag.putString("gamemode", "CREATIVE");
        tag.putBoolean("flying", true);
        PlayerIO.write(new File(new File(WorldSaves.dir(dirName), "player"), "player.dat"), tag);
        this.progress.log(String.format("Spawn: %.1f / %.1f / %.1f (MC-Spawn + Y-Offset %d)",
                spawn[0], spawn[1], spawn[2], Y_OFFSET));
    }
}
