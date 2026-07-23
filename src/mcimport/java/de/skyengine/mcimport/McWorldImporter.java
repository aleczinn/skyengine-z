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
import de.skyengine.game.world.item.Item;
import de.skyengine.game.world.item.ItemStack;
import de.skyengine.game.world.item.Items;
import de.skyengine.game.world.save.ChunkSerializer;
import de.skyengine.game.world.save.LevelData;
import de.skyengine.game.world.save.PlayerIO;
import de.skyengine.game.world.save.RegionFile;
import de.skyengine.game.world.save.WorldSaves;
import de.skyengine.mcimport.mapping.BlockMapper;
import de.skyengine.mcimport.mca.McBlockState;
import de.skyengine.mcimport.mca.McChunk;
import de.skyengine.mcimport.mca.McChunkParser;
import de.skyengine.mcimport.mca.McRegionFile;
import de.skyengine.mcimport.mca.McSection;
import de.skyengine.mcimport.nbt.NbtCompound;
import de.skyengine.mcimport.nbt.NbtList;
import de.skyengine.mcimport.nbt.NbtReader;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
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
 * <p>Aufruf: {@code ./gradlew mcImport --args="<mcWeltPfad> <weltName>"}
 */
public final class McWorldImporter {

    private static final Pattern REGION_NAME = Pattern.compile("r\\.(-?\\d+)\\.(-?\\d+)\\.mca");
    private static final int Y_OFFSET = 64;

    /* Zähler für die Abschluss-Summary. */
    private static int chunksImported, mcChunksMissing, cellsOutOfRange, chestsImported;
    private static int itemsImported, itemsSkipped, blockEntitiesSkipped;

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.out.println("Aufruf: McWorldImporter <mcWeltPfad> <weltName>");
            System.exit(1);
        }
        File mcWorld = new File(args[0]);
        String worldName = args[1];

        File regionDir = new File(mcWorld, "region");
        File[] regionFiles = regionDir.listFiles((dir, name) -> REGION_NAME.matcher(name).matches());
        if (regionFiles == null || regionFiles.length == 0) {
            System.out.println("Keine region/*.mca gefunden unter: " + regionDir.getAbsolutePath());
            System.exit(1);
        }
        Arrays.sort(regionFiles, Comparator.comparing(File::getName));

        Blocks.bootstrap(new File(Files.RESOURCES_PATH, "game/blocks"));
        BlockMapper mapper = BlockMapper.loadDefault();

        /* Ziel-Welt anlegen: level.json (worldType=imported) + player.dat aus dem MC-Spawn. */
        WorldSaves.WorldSave save = WorldSaves.create(worldName, 0);
        LevelData level = save.level();
        level.formatVersion = 1;
        level.worldType = "imported";
        level.generator = "minecraft_import";
        level.generatorVersion = 1;
        WorldSaves.save(save);
        writePlayerDat(save.dirName(), mcWorld);

        File targetRegionDir = new File(WorldSaves.dir(save.dirName()), "region");
        if (!targetRegionDir.exists() && !targetRegionDir.mkdirs()) {
            System.out.println("Region-Verzeichnis nicht anlegbar: " + targetRegionDir);
            System.exit(1);
        }

        /* Konstante Plains-Tints (MC-Biome != Engine-Biome — bewusst simpel, im Payload). */
        int[] grassTints = new int[33 * 33];
        int[] foliageTints = new int[33 * 33];
        Arrays.fill(grassTints, Tints.GRASS);
        Arrays.fill(foliageTints, Tints.FOLIAGE);

        long start = System.currentTimeMillis();
        for (File file : regionFiles) {
            Matcher m = REGION_NAME.matcher(file.getName());
            if (!m.matches()) continue;
            int rx = Integer.parseInt(m.group(1));
            int rz = Integer.parseInt(m.group(2));
            convertRegion(file, rx, rz, targetRegionDir, mapper, grassTints, foliageTints);
        }
        long elapsed = System.currentTimeMillis() - start;

        System.out.println();
        System.out.println("=== Import fertig: " + save.dirName() + " ===");
        System.out.printf("Engine-Chunks geschrieben:  %,d (%,d ms)%n", chunksImported, elapsed);
        System.out.printf("Fehlende MC-Chunks:         %,d (Quadrant bleibt Luft)%n", mcChunksMissing);
        System.out.printf("Zellen außerhalb 0..511:    %,d%n", cellsOutOfRange);
        System.out.printf("Truhen übernommen:          %,d (%,d Items, %,d Items unbekannt)%n",
                chestsImported, itemsImported, itemsSkipped);
        System.out.printf("Andere BlockEntities:       %,d übersprungen%n", blockEntitiesSkipped);
        System.exit(0);
    }

    /** Eine MC-Region (32×32 MC-Chunks) → exakt eine Engine-Region (16×16 Engine-Chunks). */
    private static void convertRegion(File mcaFile, int rx, int rz, File targetRegionDir,
                                      BlockMapper mapper, int[] grassTints, int[] foliageTints) {
        long start = System.currentTimeMillis();
        int written = 0;
        try (McRegionFile mcRegion = new McRegionFile(mcaFile);
             RegionFile target = new RegionFile(new File(targetRegionDir, "r." + rx + "." + rz + ".srg"))) {

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
                                mcChunksMissing++;
                                continue;
                            }
                            any = true;
                            copyBlocks(mc, chunk, qx * 16, qz * 16, mapper);
                            importBlockEntities(mc, chunk);
                        }
                    }
                    if (!any) continue; // komplett ungenerierter Bereich -> gar nicht speichern

                    chunk.grassTintCorners = grassTints;
                    chunk.foliageTintCorners = foliageTints;
                    byte[] payload = ChunkSerializer.serialize(chunk, "minecraft_import", 1, true, null);
                    target.write(chunkX & 15, chunkZ & 15, payload);
                    chunksImported++;
                    written++;
                }
            }
        } catch (Exception e) {
            System.out.println(mcaFile.getName() + ": FEHLER — " + e.getMessage());
            return;
        }
        if (written == 0) {
            /* Nur Header, kein Chunk (z.B. MC-Region komplett prä-1.18) -> Datei weg. */
            new File(targetRegionDir, "r." + rx + "." + rz + ".srg").delete();
            return;
        }
        System.out.println(mcaFile.getName() + " -> r." + rx + "." + rz + ".srg: "
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
    private static void copyBlocks(McChunk mc, Chunk chunk, int ox, int oz, BlockMapper mapper) {
        for (McSection section : mc.sections()) {
            /* Palette EINMAL mappen, dann nur noch Indizes nachschlagen. */
            List<McBlockState> palette = section.palette();
            int[] mapped = new int[palette.size()];
            boolean anySolid = false;
            for (int i = 0; i < mapped.length; i++) {
                mapped[i] = mapper.map(palette.get(i));
                anySolid |= mapped[i] != 0;
            }
            if (!anySolid) continue; // reine Luft-Section (auch komplett ungemappte)

            int yBase = section.y() * 16 + Y_OFFSET;
            for (int cy = 0; cy < 16; cy++) {
                int worldY = yBase + cy;
                if (worldY < 0 || worldY >= Chunk.HEIGHT) {
                    cellsOutOfRange += 256;
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
    private static void importBlockEntities(McChunk mc, Chunk chunk) {
        for (NbtCompound be : mc.blockEntities()) {
            String id = be.getString("id", "");
            if (!id.equals("minecraft:chest")) {
                blockEntitiesSkipped++;
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
                blockEntitiesSkipped++;
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
                            itemsSkipped++;
                            continue;
                        }
                        chest.getInventory().set(slot, new ItemStack(item, itemTag.getInt("Count", 1)));
                        itemsImported++;
                    } catch (Exception ignored) {
                        itemsSkipped++;
                    }
                }
            }
            chunk.setBlockEntity(lx, wy, lz, chest);
            chestsImported++;
        }
    }

    /** Spawn aus der MC-level.dat (NBT, gzip) → player.dat (Creative + fliegend, Y+64). */
    private static void writePlayerDat(String dirName, File mcWorld) {
        double x = 0.5, y = 100, z = 0.5;
        try (FileInputStream in = new FileInputStream(new File(mcWorld, "level.dat"))) {
            NbtCompound data = NbtReader.readAuto(in).requireCompound("Data");
            x = data.getInt("SpawnX", 0) + 0.5;
            y = data.getInt("SpawnY", 64) + Y_OFFSET + 1.0; // +1 Sicherheitsabstand
            z = data.getInt("SpawnZ", 0) + 0.5;
        } catch (Exception e) {
            System.out.println("level.dat nicht lesbar (" + e.getMessage() + ") — Spawn-Fallback 0/100/0");
        }
        DataTag tag = new DataTag();
        UUID uuid = UUID.randomUUID();
        tag.putLong("uuidMost", uuid.getMostSignificantBits());
        tag.putLong("uuidLeast", uuid.getLeastSignificantBits());
        tag.putDouble("x", x);
        tag.putDouble("y", y);
        tag.putDouble("z", z);
        tag.putDouble("yaw", 0);
        tag.putDouble("pitch", 0);
        tag.putString("gamemode", "CREATIVE");
        tag.putBoolean("flying", true);
        PlayerIO.write(new File(new File(WorldSaves.dir(dirName), "player"), "player.dat"), tag);
        System.out.printf("Spawn: %.1f / %.1f / %.1f (MC-Spawn + Y-Offset %d)%n", x, y, z, Y_OFFSET);
    }

    private McWorldImporter() {}
}
