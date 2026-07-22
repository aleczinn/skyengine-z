package de.skyengine.game.world.save.debug;

import de.skyengine.core.file.Files;
import de.skyengine.game.world.block.BlockPos;
import de.skyengine.game.world.block.Blocks;
import de.skyengine.game.world.block.Identifier;
import de.skyengine.game.world.block.entity.BlockEntities;
import de.skyengine.game.world.block.entity.ChestBlockEntity;
import de.skyengine.game.world.block.state.BlockState;
import de.skyengine.game.world.block.state.BlockStateCodec;
import de.skyengine.game.world.chunk.Chunk;
import de.skyengine.game.world.chunk.ChunkSection;
import de.skyengine.game.world.generator.generators.AlphaWorldGeneratorV2;
import de.skyengine.game.world.item.ItemStack;
import de.skyengine.game.world.item.Items;
import de.skyengine.game.world.save.ChunkSerializer;
import de.skyengine.game.world.save.WorldStorage;

import java.io.File;
import java.util.Arrays;

/**
 * Standalone-Werkzeug (eigene main, kein GL/Engine-Start, Muster {@code GeneratorMapExporter}):
 * generiert einen Chunk, ergänzt Edits (Treppe mit Properties, fließendes/fallendes Wasser,
 * Truhe mit Items), serialisiert ihn, stellt ihn wieder her und vergleicht ALLES.
 * Prüft außerdem Kompression + CRC-Round-Trip und die Fluid-Tick-Sammlung
 * (Quellen dürfen NICHT eingeplant werden). Exit-Code 0 = alles identisch.
 */
public final class SaveRoundTripTest {

    private static int errors = 0;

    public static void main(String[] args) throws Exception {
        Blocks.bootstrap(new File(Files.RESOURCES_PATH, "game/blocks"));

        AlphaWorldGeneratorV2 generator = new AlphaWorldGeneratorV2(123);
        Chunk chunk = new Chunk(3, -7);
        long t0 = System.currentTimeMillis();
        generator.generate(chunk);
        System.out.println("Chunk (3,-7) generiert in " + (System.currentTimeMillis() - t0) + " ms");

        /* --- Edits: Properties, Fluide, BlockEntity --- */
        chunk.setBlock(4, 200, 4, decodeId("skyengine:stone_stairs[facing=east,half=top,shape=inner_left]"));
        chunk.setBlock(5, 200, 5, decodeId("skyengine:water[falling=false,level=3]"));  // fließend -> Tick
        chunk.setBlock(6, 200, 6, decodeId("skyengine:water[falling=true,level=0]"));   // fallend  -> Tick
        chunk.setBlock(7, 200, 7, decodeId("skyengine:water[falling=false,level=0]"));  // Quelle   -> KEIN Tick

        int chestId = decodeId("skyengine:chest");
        chunk.setBlock(8, 200, 8, chestId);
        ChestBlockEntity chest = (ChestBlockEntity) BlockEntities.CHEST.create(
                new BlockPos(3 * ChunkSection.SIZE + 8, 200, -7 * ChunkSection.SIZE + 8), Blocks.getState(chestId));
        chest.getInventory().set(0, new ItemStack(Items.get(Identifier.of("skyengine:stone")), 42));
        ItemStack damaged = new ItemStack(Items.get(Identifier.of("skyengine:oak_planks")), 7);
        damaged.setDamage(5);
        chest.getInventory().set(13, damaged);
        chunk.setBlockEntity(8, 200, 8, chest);

        /* --- Serialisieren + Kompression/CRC-Round-Trip --- */
        t0 = System.currentTimeMillis();
        byte[] raw = ChunkSerializer.serialize(chunk, "alpha_v2", 1, true);
        long serializeMs = System.currentTimeMillis() - t0;
        byte[] compressed = ChunkSerializer.compress(raw);
        int crc = ChunkSerializer.crc32(raw);
        byte[] rawBack = ChunkSerializer.decompress(compressed, raw.length);
        check(Arrays.equals(raw, rawBack), "Kompression: decompress(compress(x)) == x");
        check(ChunkSerializer.crc32(rawBack) == crc, "CRC über Roh-Payload stabil");
        System.out.printf("Payload: %,d B roh -> %,d B komprimiert (%.1f %%), serialize %d ms%n",
                raw.length, compressed.length, 100.0 * compressed.length / raw.length, serializeMs);

        /* --- Wiederherstellen + Vollvergleich --- */
        Chunk restored = new Chunk(3, -7);
        t0 = System.currentTimeMillis();
        ChunkSerializer.deserialize(restored, rawBack, null);
        System.out.println("Deserialisiert in " + (System.currentTimeMillis() - t0) + " ms");

        int blockMismatches = 0;
        for (int y = 0; y < Chunk.HEIGHT; y++) {
            for (int z = 0; z < ChunkSection.SIZE; z++) {
                for (int x = 0; x < ChunkSection.SIZE; x++) {
                    if (chunk.getBlock(x, y, z) != restored.getBlock(x, y, z)) {
                        if (blockMismatches < 5) {
                            System.out.println("  Block-Abweichung bei (" + x + "," + y + "," + z + "): "
                                    + BlockStateCodec.encode(Blocks.getState(chunk.getBlock(x, y, z))) + " -> "
                                    + BlockStateCodec.encode(Blocks.getState(restored.getBlock(x, y, z))));
                        }
                        blockMismatches++;
                    }
                }
            }
        }
        check(blockMismatches == 0, "Alle " + (Chunk.HEIGHT * ChunkSection.SIZE * ChunkSection.SIZE)
                + " Blöcke identisch" + (blockMismatches > 0 ? " (" + blockMismatches + " Abweichungen)" : ""));

        check(Arrays.equals(chunk.grassTintCorners, restored.grassTintCorners), "Grass-Tint-Grid identisch");
        check(Arrays.equals(chunk.foliageTintCorners, restored.foliageTintCorners), "Foliage-Tint-Grid identisch");

        /* Truhe: Typ + alle Slots (Item-ID, Anzahl, Schaden). */
        if (restored.getBlockEntity(8, 200, 8) instanceof ChestBlockEntity restoredChest) {
            boolean slotsOk = true;
            for (int i = 0; i < ChestBlockEntity.SLOTS; i++) {
                ItemStack a = chest.getInventory().get(i);
                ItemStack b = restoredChest.getInventory().get(i);
                if (a.isEmpty() != b.isEmpty()) { slotsOk = false; break; }
                if (a.isEmpty()) continue;
                if (a.getItem() != b.getItem() || a.getCount() != b.getCount() || a.getDamage() != b.getDamage()) {
                    slotsOk = false;
                    break;
                }
            }
            check(slotsOk, "Truhen-Inventar identisch (27 Slots)");
        } else {
            check(false, "Truhen-BlockEntity wiederhergestellt");
        }

        /* Fluid-Ticks: genau die zwei fließenden Positionen, die Quelle nicht. */
        int[] ticks = restored.pendingFluidTicks == null ? new int[0] : restored.pendingFluidTicks;
        check(contains(ticks, packLocal(5, 200, 5)), "Fließendes Wasser (level=3) eingeplant");
        check(contains(ticks, packLocal(6, 200, 6)), "Fallendes Wasser eingeplant");
        check(!contains(ticks, packLocal(7, 200, 7)), "Wasser-Quelle NICHT eingeplant");
        System.out.println("Fluid-Ticks gesamt: " + ticks.length
                + " (Generator-Wasser sind Quellen und zählen nicht mit)");

        /* --- Luft-Fallback: unbekannter State (Block aus dem Spiel entfernt) --- */
        byte[] tampered = raw.clone();
        byte[] needle = "skyengine:stone_stairs[".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] replacement = "skyengine:kaput_stairs[".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        int at = indexOf(tampered, needle);
        check(at >= 0, "Treppen-State-String im Payload gefunden");
        if (at >= 0) {
            System.arraycopy(replacement, 0, tampered, at, replacement.length);
            Chunk fallback = new Chunk(3, -7);
            ChunkSerializer.deserialize(fallback, tampered, null);
            check(fallback.getBlock(4, 200, 4) == 0, "Unbekannter State wird zu Luft");
            check(fallback.getBlock(5, 200, 5) == chunk.getBlock(5, 200, 5),
                    "Übrige Blöcke der Section bleiben erhalten");
        }

        /* ================= M2: Region-Datei + WorldStorage ================= */
        System.out.println("--- Region-Storage ---");
        File dir = new File("build/tmp/region-roundtrip");
        if (dir.exists()) {
            File[] old = dir.listFiles();
            if (old != null) for (File f : old) f.delete();
        }

        Chunk other = new Chunk(18, -7); // Region (1,-1) — testet die Region-Grenze bei negativen Koordinaten
        generator.generate(other);
        byte[] payloadB = ChunkSerializer.serialize(other, "alpha_v2", 1, true);

        WorldStorage storage = new WorldStorage(dir, null, null, "test", 1, true);
        storage.writeChunk(3, -7, raw);       // Region (0,-1), landet in Sektor 1
        storage.writeChunk(18, -7, payloadB); // Region (1,-1)
        check(storage.hasChunk(3, -7) && storage.hasChunk(18, -7), "hasChunk für gespeicherte Chunks");
        check(!storage.hasChunk(99, 99), "hasChunk-Miss für nie gespeicherte Region");

        /* Wachstum erzwingen: inkompressibler 40-KB-Payload -> Move (Append), Sektor 1 wird frei. */
        byte[] big = new byte[40 * 1024];
        new java.util.Random(42).nextBytes(big);
        storage.writeChunk(3, -7, big);
        File regionFile = new File(dir, "r.0.-1.srg");
        long lengthAfterBig = regionFile.length();
        /* Neuer 1-Sektor-Chunk muss die Lücke (Sektor 1) wiederverwenden — Datei wächst nicht. */
        storage.writeChunk(4, -7, raw);
        check(regionFile.length() == lengthAfterBig, "Freigewordener Sektor wird wiederverwendet (Datei wächst nicht)");
        /* Zurück auf klein: passt in-place in die alten Sektoren, Rest wird freigegeben. */
        storage.writeChunk(3, -7, raw);

        /* Alles über eine ECHTE Datei-Neuöffnung zurücklesen. */
        storage.close();
        storage = new WorldStorage(dir, null, null, "test", 1, true);
        check(Arrays.equals(storage.readChunk(3, -7), raw), "Chunk (3,-7) nach Neuöffnung identisch (in-place + Move überlebt)");
        check(Arrays.equals(storage.readChunk(4, -7), raw), "Chunk (4,-7) nach Neuöffnung identisch (aus wiederverwendetem Sektor)");
        check(Arrays.equals(storage.readChunk(18, -7), payloadB), "Chunk (18,-7) nach Neuöffnung identisch (Nachbar-Region)");
        check(storage.readChunk(5, -7) == null, "Nie gespeicherter Chunk liefert null");
        storage.close();

        /* Korruption: ein Byte in den Daten von Chunk (4,-7) (Sektor 1) kippen -> CRC muss greifen. */
        try (java.io.RandomAccessFile rafFile = new java.io.RandomAccessFile(regionFile, "rw")) {
            rafFile.seek(4096 + 20);
            int b = rafFile.read();
            rafFile.seek(4096 + 20);
            rafFile.write(b ^ 0xFF);
        }
        storage = new WorldStorage(dir, null, null, "test", 1, true);
        check(storage.readChunk(4, -7) == null, "Korrupter Chunk wird erkannt (CRC) und als ungültig behandelt");
        check(Arrays.equals(storage.readChunk(3, -7), raw), "Nachbar-Chunk derselben Region bleibt lesbar");
        storage.close();

        System.out.println(errors == 0 ? "ROUND-TRIP OK" : "ROUND-TRIP FEHLGESCHLAGEN: " + errors + " Fehler");
        System.exit(errors == 0 ? 0 : 1);
    }

    /* Naive Byte-Suche — Payloads sind klein, reicht für den Test. */
    private static int indexOf(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) continue outer;
            }
            return i;
        }
        return -1;
    }

    private static int decodeId(String encoded) {
        BlockState state = BlockStateCodec.decode(encoded);
        if (state == null) throw new IllegalStateException("Testblock nicht gefunden: " + encoded);
        return state.getId();
    }

    /* Gleiche Packung wie Chunk.beKey / ChunkSerializer.packLocalPos. */
    private static int packLocal(int lx, int y, int lz) {
        return (lx & 31) | ((lz & 31) << 5) | ((y & 511) << 10);
    }

    private static boolean contains(int[] array, int value) {
        for (int v : array) if (v == value) return true;
        return false;
    }

    private static void check(boolean ok, String what) {
        System.out.println((ok ? "  [OK] " : "  [FEHLER] ") + what);
        if (!ok) errors++;
    }

    private SaveRoundTripTest() {}
}
