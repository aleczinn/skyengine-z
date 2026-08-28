package de.skyengine.game.world.save;

import de.skyengine.game.world.Dimension;
import de.skyengine.game.entity.Entity;
import de.skyengine.game.entity.ItemFrameEntity;
import de.skyengine.game.world.block.BlockPos;
import de.skyengine.game.world.block.Blocks;
import de.skyengine.game.world.block.Identifier;
import de.skyengine.game.world.block.entity.BlockEntity;
import de.skyengine.game.world.block.entity.BlockEntityType;
import de.skyengine.game.world.block.entity.DataTag;
import de.skyengine.game.world.block.registry.Registries;
import de.skyengine.game.world.block.state.BlockState;
import de.skyengine.game.world.block.state.BlockStateCodec;
import de.skyengine.game.world.chunk.Chunk;
import de.skyengine.game.world.chunk.ChunkSection;
import de.skyengine.game.world.chunk.palette.BitStorage;
import de.skyengine.game.world.chunk.palette.PalettedContainer;
import de.skyengine.game.world.tick.SavedTick;
import de.skyengine.game.world.tick.ScheduledTickTypes;
import de.skyengine.utils.logging.LogManager;
import de.skyengine.utils.logging.Logger;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.CRC32;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * Serialisiert einen Chunk als selbsttragenden Snapshot (Blöcke + Tints + BlockEntities)
 * und stellt ihn wieder her. BlockStates werden als stabile Strings über
 * {@link BlockStateCodec} persistiert (Runtime-IDs sind flüchtig!), dedupliziert über eine
 * chunk-weite Palette; die Section-Bit-Daten ({@link BitStorage}) werden roh übernommen.
 *
 * <p>Payload-Format v5 (unkomprimiert; Kompression/CRC über {@link #compress}/{@link #crc32},
 * CRC immer über den ROHEN Payload — Kompressionswechsel ändern die Prüfsumme nicht):
 * <pre>
 * byte  payloadVersion = 5
 * UTF   generatorId, int generatorVersion      (Provenienz — strikt getrennt von payloadVersion)
 * int   paletteCount, paletteCount × UTF        (chunk-weite State-Strings)
 * 16 ×  Section: byte mode
 *       0 = leer | 1 = Single-Value (int chunkPaletteIndex)
 *       2 = int localCount, localCount × int chunkPaletteIndex,
 *           byte bitsPerEntry, int nonAir, int longCount, longCount × long
 * byte  hasStoredTints; wenn 1: 2 × 33*33 int (grass, foliage)
 * int   beCount, beCount × { int packedLocalPos, UTF typeId, DataTag binär }
 * int   tickCount, tickCount × { UTF tickTypeId, UTF expectedBlockId,
 *                                 int x, int y, int z, int remainingTicks,
 *                                 int priority, long subOrder }
 * int   entityCount, entityCount × { UTF typeId, DataTag binär }
 * </pre>
 *
 * <p>Threading: {@link #serialize} verlangt unveränderliche Eingabedaten — entweder hält der
 * Aufrufer den Read-Lock eines Live-Chunks oder übergibt den von {@link #snapshotChunkData}
 * erzeugten detached Snapshot. {@link #deserialize} darf nur auf einem Chunk laufen, der noch nicht per
 * Status-Publish lesbar ist (Load-Job vor dem Setzen von DECORATED).
 */
public final class ChunkSerializer {

    /* v5 ist das einzige lesbare Payload-Format. */
    public static final byte PAYLOAD_VERSION = 5;

    private static final Logger LOGGER = LogManager.getLogger(ChunkSerializer.class.getName());

    private static final byte SECTION_EMPTY = 0;
    private static final byte SECTION_SINGLE = 1;
    private static final byte SECTION_BITS = 2;

    private static final int TINT_GRID_SIZE = 33 * 33;
    /** Gemeinsamer Dekompressionsdeckel für RegionFile und direkte Werkzeug-Aufrufe. */
    static final int MAX_RAW_LENGTH = 64 * 1024 * 1024;

    /* Einmal-pro-String-Warnung über alle Chunks hinweg (sonst Log-Flut bei großen Welten). */
    private static final Set<String> warnedStates = ConcurrentHashMap.newKeySet();
    private static final Set<String> warnedTickTypes = ConcurrentHashMap.newKeySet();
    private static final Set<String> warnedTickTargets = ConcurrentHashMap.newKeySet();

    /* --- Serialisieren --- */

    /**
     * Zieht auf dem TICK-THREAD (aus {@link WorldStorage#enqueueSave}) die BlockEntity-Tags dieses
     * Chunks. {@code be.save()} liest hier den Live-Zustand (z.B. Truhen-Inventar) auf demselben
     * Thread, der ihn auch über das GUI mutiert — daher kein Race. Der IO-Thread serialisiert
     * später nur diese Kopie ({@link #serialize}). Kein Lock nötig: die BlockEntity-Map wird
     * ausschließlich auf dem Tick-Thread strukturell verändert.
     */
    public static List<SavedBlockEntity> snapshotBlockEntities(Chunk chunk) {
        List<SavedBlockEntity> out = new ArrayList<>();
        for (BlockEntity be : chunk.blockEntities()) {
            Identifier id = Registries.BLOCK_ENTITY.idOf(be.getType());
            if (id == null) {
                LOGGER.warning("BlockEntity ohne Registry-Eintrag wird nicht gespeichert: " + be.getClass().getName());
                continue;
            }
            BlockPos pos = be.getPos();
            DataTag tag = new DataTag();
            be.save(tag);
            out.add(new SavedBlockEntity(packLocalPos(pos.x() & 31, pos.y(), pos.z() & 31), id.toString(), tag));
        }
        return out;
    }

    /** Zieht persistente Entities; bewegte Drops und TNT bleiben bewusst fluechtig. */
    public static List<SavedEntity> snapshotEntities(Chunk chunk) {
        List<SavedEntity> out = new ArrayList<>();
        for (Entity entity : chunk.entities()) {
            if (entity.isRemoved()) continue;
            if (entity instanceof ItemFrameEntity frame) {
                DataTag tag = new DataTag()
                        .putInt("anchor_x", frame.getAnchorX())
                        .putInt("anchor_y", frame.getAnchorY())
                        .putInt("anchor_z", frame.getAnchorZ())
                        .putString("direction", frame.getDirection().name())
                        .putInt("rotation", frame.getRotation())
                        .putTag("item", frame.getItem().save());
                out.add(new SavedEntity(Identifier.of("item_frame").toString(), tag));
            } else if (entity instanceof de.skyengine.game.entity.MinecartEntity minecart) {
                DataTag tag = new DataTag()
                        .putDouble("x", minecart.x).putDouble("y", minecart.y).putDouble("z", minecart.z)
                        .putDouble("motion_x", minecart.motionX)
                        .putDouble("motion_y", minecart.motionY)
                        .putDouble("motion_z", minecart.motionZ)
                        .putDouble("yaw", minecart.yaw)
                        .putDouble("damage", minecart.getDamage())
                        .putInt("hurt_time", minecart.getHurtTime())
                        .putInt("hurt_direction", minecart.getHurtDirection());
                out.add(new SavedEntity(Identifier.of("minecart").toString(), tag));
            }
        }
        return out;
    }

    /**
     * Kopiert alle vom Chunk-Payload benoetigten Block- und Tintdaten. Der Aufrufer muss den
     * Read-Lock des Quellchunks halten. Der Rueckgabewert teilt weder Paletten-/Bit-Arrays noch
     * Tint-Arrays mit dem Live-Chunk und kann deshalb spaeter auf dem IO-Thread serialisiert werden.
     */
    public static Chunk snapshotChunkData(Chunk source) {
        Chunk snapshot = new Chunk(source.chunkX, source.chunkZ);
        for (int s = 0; s < Chunk.SECTIONS; s++) {
            ChunkSection section = source.getSection(s);
            if (section == null || section.isEmpty()) continue;
            PalettedContainer container = section.container();
            int[] palette = container.paletteEntries();
            BitStorage storage = container.storage();
            BitStorage storageCopy = storage == null ? null
                    : new BitStorage(storage.bitsPerEntry(), storage.size(), storage.raw().clone());
            snapshot.installSection(s, new ChunkSection(new PalettedContainer(
                    ChunkSection.VOLUME, palette, palette.length, storageCopy, container.nonAir())));
        }
        snapshot.grassTintCorners = source.grassTintCorners == null ? null : source.grassTintCorners.clone();
        snapshot.foliageTintCorners = source.foliageTintCorners == null ? null : source.foliageTintCorners.clone();
        return snapshot;
    }

    /**
     * Die Chunkdaten dürfen während des Aufrufs nicht mutieren. Tints werden nur mit
     * {@code storeTints} geschrieben; {@code scheduledTicks} ist der auf dem Tick-Thread gezogene
     * Queue-Snapshot des Chunks (null = keine). {@code blockEntities} sind die auf dem Tick-Thread
     * vorserialisierten BlockEntity-Tags ({@link #snapshotBlockEntities}); der IO-Thread ruft
     * {@code be.save()} bewusst NICHT selbst auf (Race mit GUI-Mutationen). null = keine.
     */
    public static byte[] serialize(Chunk chunk, String generatorId, int generatorVersion,
                                   boolean storeTints, List<SavedTick> scheduledTicks,
                                   List<SavedBlockEntity> blockEntities) {
        return serialize(chunk, generatorId, generatorVersion, storeTints, scheduledTicks,
                blockEntities, null);
    }

    public static byte[] serialize(Chunk chunk, String generatorId, int generatorVersion,
                                   boolean storeTints, List<SavedTick> scheduledTicks,
                                   List<SavedBlockEntity> blockEntities,
                                   List<SavedEntity> entities) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(64 * 1024);
            DataOutputStream out = new DataOutputStream(bytes);

            out.writeByte(PAYLOAD_VERSION);
            out.writeUTF(generatorId);
            out.writeInt(generatorVersion);

            /* Pass 1: chunk-weite Palette aufbauen (Runtime-ID -> Palette-Index). */
            Map<Integer, Integer> paletteIndex = new LinkedHashMap<>();
            for (int s = 0; s < Chunk.SECTIONS; s++) {
                ChunkSection section = chunk.getSection(s);
                if (section == null || section.isEmpty()) continue;
                for (int stateId : section.container().paletteEntries()) {
                    paletteIndex.putIfAbsent(stateId, paletteIndex.size());
                }
            }
            out.writeInt(paletteIndex.size());
            for (int stateId : paletteIndex.keySet()) {
                out.writeUTF(BlockStateCodec.encode(Blocks.getState(stateId)));
            }

            /* Pass 2: Sections. */
            for (int s = 0; s < Chunk.SECTIONS; s++) {
                ChunkSection section = chunk.getSection(s);
                if (section == null || section.isEmpty()) {
                    out.writeByte(SECTION_EMPTY);
                    continue;
                }
                PalettedContainer container = section.container();
                BitStorage storage = container.storage();
                int[] local = container.paletteEntries();
                if (storage == null) {
                    out.writeByte(SECTION_SINGLE);
                    out.writeInt(paletteIndex.get(local[0]));
                    continue;
                }
                out.writeByte(SECTION_BITS);
                out.writeInt(local.length);
                for (int stateId : local) out.writeInt(paletteIndex.get(stateId));
                out.writeByte(storage.bitsPerEntry());
                out.writeInt(container.nonAir());
                long[] raw = storage.raw();
                out.writeInt(raw.length);
                for (long word : raw) out.writeLong(word);
            }

            /* Tints. */
            boolean tints = storeTints && chunk.grassTintCorners != null && chunk.foliageTintCorners != null;
            out.writeByte(tints ? 1 : 0);
            if (tints) {
                for (int v : chunk.grassTintCorners) out.writeInt(v);
                for (int v : chunk.foliageTintCorners) out.writeInt(v);
            }

            /* BlockEntities: auf dem Tick-Thread vorserialisiert (snapshotBlockEntities) — hier nur
               noch die fertigen Tags rausschreiben. Kein be.save() auf dem IO-Thread. */
            List<SavedBlockEntity> bes = blockEntities == null ? List.of() : blockEntities;
            out.writeInt(bes.size());
            for (SavedBlockEntity be : bes) {
                out.writeInt(be.packedLocalPos());
                out.writeUTF(be.typeId());
                DataTagIO.write(be.tag(), out);
            }

            /* Scheduled-Ticks (v3): Typ, stabile Zielidentität und Reihenfolge. */
            out.writeInt(scheduledTicks == null ? 0 : scheduledTicks.size());
            if (scheduledTicks != null) {
                for (SavedTick tick : scheduledTicks) {
                    out.writeUTF(tick.type());
                    out.writeUTF(tick.expectedBlock() == null ? "" : tick.expectedBlock());
                    out.writeInt(tick.x());
                    out.writeInt(tick.y());
                    out.writeInt(tick.z());
                    out.writeInt(tick.remainingTicks());
                    out.writeInt(tick.priority());
                    out.writeLong(tick.subOrder());
                }
            }

            /* Persistente Entities (v4), auf dem Tick-Thread bereits als Tags eingefroren. */
            List<SavedEntity> savedEntities = entities == null ? List.of() : entities;
            out.writeInt(savedEntities.size());
            for (SavedEntity entity : savedEntities) {
                out.writeUTF(entity.typeId());
                DataTagIO.write(entity.tag(), out);
            }

            return bytes.toByteArray();
        } catch (IOException e) {
            /* ByteArrayOutputStream wirft real nie — nur DataTagIO-Fehler landen hier. */
            throw new UncheckedIOException("Chunk-Serialisierung fehlgeschlagen ("
                    + chunk.chunkX + ", " + chunk.chunkZ + ")", e);
        }
    }

    /* --- Deserialisieren --- */

    /**
     * Stellt den Chunk aus einem Payload wieder her. {@code world} darf null sein
     * (Headless-Tests) — dann bleibt {@code setWorld} an BlockEntities aus.
     * Wirft {@link IOException} bei jedem Formatfehler; der Aufrufer fällt dann auf
     * Regeneration zurück.
     */
    public static void deserialize(Chunk chunk, byte[] payload, Dimension world) throws IOException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload));

        byte version = in.readByte();
        if (version != PAYLOAD_VERSION) {
            throw new IOException("Unbekannte Chunk-Payload-Version " + version);
        }
        in.readUTF();  // generatorId — Provenienz, aktuell nur Format-Bestandteil
        in.readInt();  // generatorVersion

        /* Chunk-Palette: Strings -> Runtime-IDs; unbekannte States werden Luft (+ 1 Warnung). */
        int paletteCount = in.readInt();
        if (paletteCount < 0 || paletteCount > ChunkSection.VOLUME * Chunk.SECTIONS) {
            throw new IOException("Ungültige Paletten-Größe " + paletteCount);
        }
        int[] palette = new int[paletteCount];
        boolean[] unknown = new boolean[paletteCount];
        for (int i = 0; i < paletteCount; i++) {
            String encoded = in.readUTF();
            BlockState state = BlockStateCodec.decode(encoded);
            if (state == null) {
                palette[i] = 0;
                unknown[i] = true;
                if (warnedStates.add(encoded)) {
                    LOGGER.warning("Unbekannter Block-State in Save-Datei, ersetze durch Luft: " + encoded);
                }
            } else {
                palette[i] = state.getId();
            }
        }

        for (int s = 0; s < Chunk.SECTIONS; s++) {
            byte mode = in.readByte();
            switch (mode) {
                case SECTION_EMPTY -> chunk.installSection(s, null);
                case SECTION_SINGLE -> {
                    int paletteIndex = checkIndex(in.readInt(), paletteCount);
                    int stateId = palette[paletteIndex];
                    if (stateId == 0) {
                        chunk.installSection(s, null);
                    } else {
                        chunk.installSection(s, new ChunkSection(new PalettedContainer(ChunkSection.VOLUME, stateId)));
                    }
                }
                case SECTION_BITS -> {
                    int localCount = in.readInt();
                    /* Die chunkweite Palette dedupliziert State-IDs. Alte lokale Paletten
                       koennen dagegen unbenutzte oder doppelte Slots enthalten; deshalb darf
                       localCount groesser als paletteCount sein. Obergrenze ist die Zahl der
                       Zellen einer Section: mehr Slots koennen keinen gueltigen Zellindex
                       repraesentieren und waeren nur eine unkontrollierte Allokation. */
                    if (localCount < 1 || localCount > ChunkSection.VOLUME) {
                        throw new IOException("Ungültige Section-Palette (" + localCount + " Einträge)");
                    }
                    int[] local = new int[localCount];
                    boolean anyUnknown = false;
                    for (int i = 0; i < localCount; i++) {
                        int idx = checkIndex(in.readInt(), paletteCount);
                        local[i] = palette[idx];
                        anyUnknown |= unknown[idx];
                    }
                    int bitsPerEntry = in.readByte();
                    if (bitsPerEntry < 1 || bitsPerEntry > 31) {
                        throw new IOException("Ungültige Bitbreite " + bitsPerEntry);
                    }
                    int nonAir = in.readInt();
                    if (nonAir < 0 || nonAir > ChunkSection.VOLUME) {
                        throw new IOException("Ungültige Non-Air-Anzahl " + nonAir);
                    }
                    int longCount = in.readInt();
                    int expectedLongCount = (int) (((long) ChunkSection.VOLUME * bitsPerEntry + 63) / 64);
                    if (longCount != expectedLongCount) {
                        throw new IOException("Ungültige BitStorage-Länge " + longCount
                                + " statt " + expectedLongCount);
                    }
                    long[] data = new long[longCount];
                    for (int i = 0; i < longCount; i++) data[i] = in.readLong();

                    BitStorage storage = new BitStorage(bitsPerEntry, ChunkSection.VOLUME, data);
                    /* Wurden Paletten-Einträge zu Luft, stimmt der gespeicherte nonAir nicht mehr. */
                    if (anyUnknown) {
                        nonAir = 0;
                        for (int i = 0; i < ChunkSection.VOLUME; i++) {
                            int idx = storage.get(i);
                            if (idx < localCount && local[idx] != 0) nonAir++;
                        }
                    }
                    PalettedContainer container = new PalettedContainer(ChunkSection.VOLUME, local, localCount, storage, nonAir);
                    chunk.installSection(s, nonAir == 0 ? null : new ChunkSection(container));
                }
                default -> throw new IOException("Unbekannter Section-Modus " + mode);
            }
        }

        /* Tints. */
        if (in.readByte() != 0) {
            int[] grass = new int[TINT_GRID_SIZE];
            int[] foliage = new int[TINT_GRID_SIZE];
            for (int i = 0; i < TINT_GRID_SIZE; i++) grass[i] = in.readInt();
            for (int i = 0; i < TINT_GRID_SIZE; i++) foliage[i] = in.readInt();
            chunk.grassTintCorners = grass;
            chunk.foliageTintCorners = foliage;
        }

        /* BlockEntities. */
        int beCount = in.readInt();
        if (beCount < 0 || beCount > ChunkSection.VOLUME * Chunk.SECTIONS) {
            throw new IOException("Ungültige BlockEntity-Anzahl " + beCount);
        }
        for (int i = 0; i < beCount; i++) {
            int packed = in.readInt();
            String typeId = in.readUTF();
            DataTag tag = DataTagIO.read(in);

            BlockEntityType<?> type = Registries.BLOCK_ENTITY.get(Identifier.of(typeId));
            if (type == null) {
                LOGGER.warning("Unbekannter BlockEntity-Typ in Save-Datei, überspringe: " + typeId);
                continue;
            }
            int lx = packed & 31;
            int lz = (packed >> 5) & 31;
            int y = (packed >> 10) & 511;
            BlockPos pos = new BlockPos((chunk.chunkX << ChunkSection.SHIFT) + lx, y,
                    (chunk.chunkZ << ChunkSection.SHIFT) + lz);
            BlockEntity be = type.create(pos, Blocks.getState(chunk.getBlock(lx, y, lz)));
            be.load(tag);
            if (world != null) be.setWorld(world);
            chunk.setBlockEntity(lx, y, lz, be);
        }
        /* Scheduled-Ticks. */
        {
            int tickCount = in.readInt();
            if (tickCount < 0 || tickCount > ChunkSection.VOLUME * Chunk.SECTIONS) {
                throw new IOException("Ungültige Tick-Anzahl " + tickCount);
            }
            List<SavedTick> ticks = tickCount == 0 ? null : new ArrayList<>(tickCount);
            for (int i = 0; i < tickCount; i++) {
                String type = in.readUTF();
                String expectedBlock = in.readUTF();
                if (expectedBlock != null && expectedBlock.isEmpty()) expectedBlock = null;
                int x = in.readInt();
                int y = in.readInt();
                int z = in.readInt();
                int remaining = in.readInt();
                int priority = in.readInt();
                long subOrder = in.readLong();
                /* Unbekannter Typ (Save aus neuerer Engine): Eintrag überspringen, Stream
                   bleibt intakt (feste Feldbreiten). */
                if (ScheduledTickTypes.get(type) == null) {
                    if (warnedTickTypes.add(type)) {
                        LOGGER.warning("Unbekannter Tick-Typ in Save-Datei, überspringe: " + type);
                    }
                    continue;
                }
                if ((ScheduledTickTypes.BLOCK.equals(type) || ScheduledTickTypes.BLOCK_EVENT.equals(type))
                        && expectedBlock != null
                        && Registries.BLOCK.get(Identifier.of(expectedBlock)) == null) {
                    if (warnedTickTargets.add(expectedBlock)) {
                        LOGGER.warning("Unbekannter erwarteter Block in Save-Datei, Tick wird uebersprungen: "
                                + expectedBlock);
                    }
                    continue;
                }
                /* Absolute Koordinaten validieren: korrupte Daten dürfen keine Ticks in
                   fremde Chunks streuen. Null/negative Restzeiten sind gültige, bereits
                   fällige Vanilla-Ticks und bleiben für die Lade-Reihenfolge erhalten. */
                if ((x >> ChunkSection.SHIFT) != chunk.chunkX || (z >> ChunkSection.SHIFT) != chunk.chunkZ
                        || y < 0 || y >= Chunk.HEIGHT) {
                    LOGGER.warning("Tick außerhalb des Chunks (" + chunk.chunkX + ", " + chunk.chunkZ
                            + ") bei (" + x + ", " + y + ", " + z + ") — übersprungen");
                    continue;
                }
                if (subOrder < 0 || subOrder == Long.MAX_VALUE) {
                    LOGGER.warning("Ungueltige Tick-Suborder " + subOrder + " bei ("
                            + x + ", " + y + ", " + z + ") - uebersprungen");
                    continue;
                }
                ticks.add(new SavedTick(type, expectedBlock, x, y, z,
                        remaining, priority, subOrder));
            }
            chunk.pendingScheduledTicks = ticks == null || ticks.isEmpty() ? null : ticks;
            /* Beim Manager anmelden: Dimension.restorePendingScheduledTicks pollt nur noch die
               Announce-Queue (kein Voll-Walk mehr). No-op ohne Manager (Tools/Tests). */
            chunk.announceTickRestore();
        }

        {
            int entityCount = in.readInt();
            if (entityCount < 0 || entityCount > ChunkSection.VOLUME * Chunk.SECTIONS) {
                throw new IOException("Ungueltige Entity-Anzahl " + entityCount);
            }
            for (int i = 0; i < entityCount; i++) {
                String typeId = in.readUTF();
                DataTag tag = DataTagIO.read(in);
                Identifier entityType;
                try {
                    entityType = Identifier.of(typeId);
                } catch (IllegalArgumentException invalidIdentifier) {
                    LOGGER.warning("Ungültiger persistenter Entity-Typ, überspringe: " + typeId);
                    continue;
                }
                if (Identifier.of("minecart").equals(entityType)) {
                    double x = tag.getDouble("x", Double.NaN);
                    double y = tag.getDouble("y", Double.NaN);
                    double z = tag.getDouble("z", Double.NaN);
                    if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)
                            || ((int) Math.floor(x) >> ChunkSection.SHIFT) != chunk.chunkX
                            || ((int) Math.floor(z) >> ChunkSection.SHIFT) != chunk.chunkZ
                            || y < -64 || y > Chunk.HEIGHT + 64) {
                        LOGGER.warning("Minecart ausserhalb seines Chunks wird uebersprungen");
                        continue;
                    }
                    de.skyengine.game.entity.MinecartEntity minecart =
                            new de.skyengine.game.entity.MinecartEntity();
                    minecart.setPosition(x, y, z);
                    minecart.motionX = tag.getDouble("motion_x", 0);
                    minecart.motionY = tag.getDouble("motion_y", 0);
                    minecart.motionZ = tag.getDouble("motion_z", 0);
                    minecart.setRotation((float) tag.getDouble("yaw", 0), 0);
                    minecart.setDamage((float) tag.getDouble("damage", 0));
                    minecart.setHurtTime(tag.getInt("hurt_time", 0));
                    minecart.setHurtDirection(tag.getInt("hurt_direction", 1));
                    chunk.addEntity(minecart);
                    continue;
                }
                if (!Identifier.of("item_frame").equals(entityType)) {
                    LOGGER.warning("Unbekannter persistenter Entity-Typ, ueberspringe: " + typeId);
                    continue;
                }
                int x = tag.getInt("anchor_x", Integer.MIN_VALUE);
                int y = tag.getInt("anchor_y", Integer.MIN_VALUE);
                int z = tag.getInt("anchor_z", Integer.MIN_VALUE);
                if ((x >> ChunkSection.SHIFT) != chunk.chunkX || (z >> ChunkSection.SHIFT) != chunk.chunkZ
                        || y < 0 || y >= Chunk.HEIGHT) {
                    LOGGER.warning("Item Frame ausserhalb seines Chunks wird uebersprungen: ("
                            + x + ", " + y + ", " + z + ")");
                    continue;
                }
                de.skyengine.game.world.block.Direction direction;
                try {
                    direction = de.skyengine.game.world.block.Direction.valueOf(
                            tag.getString("direction", "NORTH"));
                } catch (IllegalArgumentException badDirection) {
                    LOGGER.warning("Item Frame mit ungueltiger Richtung wird uebersprungen");
                    continue;
                }
                ItemFrameEntity frame = new ItemFrameEntity(x, y, z, direction);
                frame.loadContent(de.skyengine.game.world.item.ItemStack.load(tag.getTag("item")),
                        tag.getInt("rotation", 0));
                chunk.addEntity(frame);
            }
        }
    }

    /* Gleiche Packung wie Chunk.beKey (x | z<<5 | y<<10). */
    private static int packLocalPos(int lx, int y, int lz) {
        return (lx & 31) | ((lz & 31) << 5) | ((y & 511) << 10);
    }

    /** Liest die bis einschließlich des alten Comparator-State-Modells gespeicherte Stärke. */
    private static int checkIndex(int index, int paletteCount) throws IOException {
        if (index < 0 || index >= paletteCount) {
            throw new IOException("Paletten-Index außerhalb des Bereichs: " + index + " / " + paletteCount);
        }
        return index;
    }

    /* --- Kompression + Prüfsumme (CRC immer über den ROHEN Payload) --- */

    public static byte[] compress(byte[] raw) {
        Deflater deflater = new Deflater(Deflater.BEST_SPEED);
        try {
            deflater.setInput(raw);
            deflater.finish();
            ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(64, raw.length / 4));
            byte[] buffer = new byte[8192];
            while (!deflater.finished()) {
                out.write(buffer, 0, deflater.deflate(buffer));
            }
            return out.toByteArray();
        } finally {
            deflater.end();
        }
    }

    public static byte[] decompress(byte[] compressed, int rawLength) throws IOException {
        if (rawLength <= 0 || rawLength > MAX_RAW_LENGTH) {
            throw new IOException("Ungültige Dekompressionsgröße " + rawLength);
        }
        Inflater inflater = new Inflater();
        try {
            inflater.setInput(compressed);
            byte[] raw = new byte[rawLength];
            int offset = 0;
            while (offset < rawLength && !inflater.finished()) {
                int n = inflater.inflate(raw, offset, rawLength - offset);
                if (n == 0 && inflater.needsInput()) {
                    throw new IOException("Deflate-Strom unvollständig (" + offset + "/" + rawLength + " Bytes)");
                }
                offset += n;
            }
            if (offset != rawLength) {
                throw new IOException("Deflate-Strom liefert " + offset + " statt " + rawLength + " Bytes");
            }
            return raw;
        } catch (DataFormatException e) {
            throw new IOException("Deflate-Strom beschädigt", e);
        } finally {
            inflater.end();
        }
    }

    public static int crc32(byte[] raw) {
        CRC32 crc = new CRC32();
        crc.update(raw);
        return (int) crc.getValue();
    }

    private ChunkSerializer() {}
}
