package de.skyengine.game.world.structure;

import de.skyengine.game.world.block.Blocks;
import de.skyengine.game.world.block.Identifier;
import de.skyengine.game.world.block.entity.BlockEntityType;
import de.skyengine.game.world.block.entity.DataTag;
import de.skyengine.game.world.block.registry.Registries;
import de.skyengine.game.world.block.state.BlockStateCodec;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;

/** Kanonische, immutable Runtime-Repräsentation einer nativen Struktur. */
public final class StructureTemplate {
    public static final int MAX_AXIS = 256;
    public static final long MAX_VOLUME = 16_000_000L;
    public static final int MAX_CELLS = 2_000_000;

    /** Persistenter Snapshot; der Typ schuetzt vor Daten, die auf den falschen Block geladen werden. */
    public static final class BlockEntitySnapshot {
        private final Identifier type;
        private final DataTag data;

        public BlockEntitySnapshot(Identifier type, DataTag data) {
            if (type == null) throw new IllegalArgumentException("BlockEntity-Typ fehlt");
            if (data == null) throw new IllegalArgumentException("BlockEntity-Daten fehlen");
            this.type = type;
            this.data = data.copy();
        }

        public Identifier type() { return type; }
        public DataTag data() { return data.copy(); }

        @Override public boolean equals(Object other) {
            return other instanceof BlockEntitySnapshot snapshot
                    && type.equals(snapshot.type) && data.equals(snapshot.data);
        }

        @Override public int hashCode() { return 31 * type.hashCode() + data.raw().hashCode(); }
    }

    /** AIR als State ist explizit; eine fehlende Cell bedeutet IGNORE/STRUCTURE_VOID. */
    public record Cell(int x, int y, int z, int state, BlockEntitySnapshot blockEntity) {
        public Cell(int x, int y, int z, int state) { this(x, y, z, state, null); }
    }

    private final Identifier id;
    private final int sizeX, sizeY, sizeZ;
    private final int anchorX, anchorY, anchorZ;
    private final List<Cell> cells;
    private final String fingerprint;

    public StructureTemplate(Identifier id, int sizeX, int sizeY, int sizeZ,
                             int anchorX, int anchorY, int anchorZ, List<Cell> cells) {
        if (id == null) throw new IllegalArgumentException("Structure-ID fehlt");
        if (sizeX <= 0 || sizeY <= 0 || sizeZ <= 0
                || sizeX > MAX_AXIS || sizeY > MAX_AXIS || sizeZ > MAX_AXIS) {
            throw new IllegalArgumentException("Ungueltige Struktur-Groesse "
                    + sizeX + "x" + sizeY + "x" + sizeZ);
        }
        if ((long) sizeX * sizeY * sizeZ > MAX_VOLUME) throw new IllegalArgumentException("Struktur zu gross");
        if (anchorX < 0 || anchorX >= sizeX || anchorY < 0 || anchorY >= sizeY
                || anchorZ < 0 || anchorZ >= sizeZ) throw new IllegalArgumentException("Anker ausserhalb der Struktur");
        if (cells.size() > MAX_CELLS) throw new IllegalArgumentException("Zu viele Strukturzellen");
        ArrayList<Cell> copy = new ArrayList<>(cells);
        copy.sort(Comparator.comparingInt(Cell::y).thenComparingInt(Cell::z).thenComparingInt(Cell::x));
        long previous = Long.MIN_VALUE;
        for (Cell cell : copy) {
            if (cell.x < 0 || cell.x >= sizeX || cell.y < 0 || cell.y >= sizeY
                    || cell.z < 0 || cell.z >= sizeZ) throw new IllegalArgumentException("Strukturzelle ausserhalb der Groesse");
            if (Blocks.getState(cell.state) == null) throw new IllegalArgumentException("Unbekannte Blockstate-ID " + cell.state);
            if (cell.blockEntity != null) {
                BlockEntityType<?> expected = Blocks.getState(cell.state).getBlock().getBlockEntityType();
                BlockEntityType<?> stored = Registries.BLOCK_ENTITY.get(cell.blockEntity.type());
                if (stored == null) throw new IllegalArgumentException(
                        "Unbekannter BlockEntity-Typ " + cell.blockEntity.type());
                if (stored != expected) throw new IllegalArgumentException("BlockEntity-Typ "
                        + cell.blockEntity.type() + " passt nicht zum Block bei "
                        + cell.x + "," + cell.y + "," + cell.z);
                if (!stored.isStructureSerializable()) throw new IllegalArgumentException(
                        "BlockEntity-Typ ist nicht in Structures speicherbar: " + cell.blockEntity.type());
            }
            long key = ((long) cell.y << 16) | ((long) cell.z << 8) | cell.x;
            if (key == previous) throw new IllegalArgumentException("Doppelte Strukturzelle bei "
                    + cell.x + "," + cell.y + "," + cell.z);
            previous = key;
        }
        this.id = id;
        this.sizeX = sizeX;
        this.sizeY = sizeY;
        this.sizeZ = sizeZ;
        this.anchorX = anchorX;
        this.anchorY = anchorY;
        this.anchorZ = anchorZ;
        this.cells = List.copyOf(copy);
        this.fingerprint = fingerprint(copy);
    }

    public Identifier id() { return id; }
    public int sizeX() { return sizeX; }
    public int sizeY() { return sizeY; }
    public int sizeZ() { return sizeZ; }
    public int anchorX() { return anchorX; }
    public int anchorY() { return anchorY; }
    public int anchorZ() { return anchorZ; }
    public List<Cell> cells() { return cells; }
    public String fingerprint() { return fingerprint; }
    public boolean hasExplicitAir() { return cells.stream().anyMatch(c -> c.state == Blocks.AIR); }

    private String fingerprint(List<Cell> cells) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(this.id.toString().getBytes(StandardCharsets.UTF_8));
            updateInt(digest, sizeX); updateInt(digest, sizeY); updateInt(digest, sizeZ);
            updateInt(digest, anchorX); updateInt(digest, anchorY); updateInt(digest, anchorZ);
            for (Cell cell : cells) {
                updateInt(digest, cell.x); updateInt(digest, cell.y); updateInt(digest, cell.z);
                digest.update(BlockStateCodec.encode(Blocks.getState(cell.state)).getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
                if (cell.blockEntity != null) {
                    digest.update(cell.blockEntity.type().toString().getBytes(StandardCharsets.UTF_8));
                    digest.update((byte) 0);
                    DataTagNbtCodec.updateDigest(digest, cell.blockEntity.data());
                }
                digest.update((byte) 0xFF);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void updateInt(MessageDigest digest, int value) {
        digest.update((byte) (value >>> 24)); digest.update((byte) (value >>> 16));
        digest.update((byte) (value >>> 8)); digest.update((byte) value);
    }
}
