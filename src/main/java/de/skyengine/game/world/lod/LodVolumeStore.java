package de.skyengine.game.world.lod;

import de.skyengine.game.world.chunk.Chunk;
import de.skyengine.game.world.save.RegionFile;
import de.skyengine.utils.logging.LogManager;
import de.skyengine.utils.logging.Logger;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * Atomarer Column-Cache unter {@code lod/volumes-v5}. Ein Region-Eintrag enthaelt fuer
 * {@code (Level, X, Z)} den vollstaendigen vertikalen Stack. Dadurch benoetigt ein warmer
 * L0-Column einen statt 16 synchronisierter Region-Reads und kann niemals nur teilweise
 * persistiert sein. volumes-v4 bleibt als regenerierbarer Altcache unangetastet.
 */
public final class LodVolumeStore implements AutoCloseable {

    private static final int MAGIC = 0x4C563033; // LV03, atomare vertikale Column

    public record Column(int x, int z, int level, LodVoxelSection[] sections) {
        public Column {
            int expected = verticalNodes(level);
            if (sections == null || sections.length != expected) {
                throw new IllegalArgumentException("LOD-Column L" + level + " braucht "
                        + expected + " Sektionen");
            }
            sections = sections.clone();
            for (int y = 0; y < sections.length; y++) {
                LodVoxelSection section = sections[y];
                if (section == null || section.nodeX != x || section.nodeY != y
                        || section.nodeZ != z || section.level != level) {
                    throw new IllegalArgumentException("Ungueltige LOD-Column-Sektion y=" + y);
                }
            }
        }

        @Override public LodVoxelSection[] sections() { return this.sections.clone(); }
        LodVoxelSection section(int y) {
            return y < 0 || y >= this.sections.length ? null : this.sections[y];
        }
    }

    private record RegionKey(int level, int rx, int rz) {}

    private final Logger logger = LogManager.getLogger(LodVolumeStore.class.getName());
    private final File root;
    private final int fingerprint;
    private final Map<RegionKey, RegionFile> regions = new HashMap<>();

    public LodVolumeStore(File lodDirectory, int fingerprint) {
        this.root = new File(lodDirectory, "volumes-v5");
        this.fingerprint = fingerprint;
    }

    public synchronized Column readColumn(int nodeX, int nodeZ, int level) {
        try {
            RegionFile region = region(level, Math.floorDiv(nodeX, 16),
                    Math.floorDiv(nodeZ, 16), false);
            if (region == null) return null;
            byte[] payload = region.read(Math.floorMod(nodeX, 16), Math.floorMod(nodeZ, 16));
            if (payload == null) return null;
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload));
            if (in.readInt() != MAGIC || in.readInt() != this.fingerprint
                    || in.readInt() != nodeX || in.readInt() != nodeZ
                    || in.readUnsignedByte() != level) return null;
            int count = in.readUnsignedByte();
            if (count != verticalNodes(level)) return null;
            LodVoxelSection[] sections = new LodVoxelSection[count];
            for (int y = 0; y < count; y++) {
                int completeness = in.readUnsignedByte();
                sections[y] = LodVoxelSection.readStorage(in, nodeX, y, nodeZ, level,
                        completeness == 1 ? LodVoxelSection.Completeness.CANONICAL
                                : LodVoxelSection.Completeness.PROVISIONAL);
            }
            if (in.available() != 0) return null;
            return new Column(nodeX, nodeZ, level, sections);
        } catch (Exception e) {
            this.logger.warning("Volumen-LOD-Column nicht lesbar: L" + level + " ("
                    + nodeX + ", " + nodeZ + ")", e);
            return null;
        }
    }

    public synchronized LodVoxelSection read(int nodeX, int nodeY, int nodeZ, int level) {
        Column column = this.readColumn(nodeX, nodeZ, level);
        return column == null ? null : column.section(nodeY);
    }

    public synchronized boolean writeColumn(Column column) {
        try {
            long estimate = 32L;
            for (LodVoxelSection section : column.sections) estimate += 2L + section.estimatedBytes();
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(
                    (int) Math.min(4L << 20, estimate));
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeInt(MAGIC);
            out.writeInt(this.fingerprint);
            out.writeInt(column.x);
            out.writeInt(column.z);
            out.writeByte(column.level);
            out.writeByte(column.sections.length);
            for (LodVoxelSection section : column.sections) {
                out.writeByte(section.completeness() == LodVoxelSection.Completeness.CANONICAL ? 1 : 0);
                section.writeStorage(out);
            }
            out.flush();
            RegionFile region = region(column.level, Math.floorDiv(column.x, 16),
                    Math.floorDiv(column.z, 16), true);
            region.write(Math.floorMod(column.x, 16), Math.floorMod(column.z, 16), bytes.toByteArray());
            return true;
        } catch (Exception e) {
            this.logger.warning("Volumen-LOD-Column nicht schreibbar: L" + column.level + " ("
                    + column.x + ", " + column.z + ")", e);
            return false;
        }
    }

    /** Test-/Kompatibilitaetshelfer. Der produktive Pfad schreibt immer komplette Columns. */
    public synchronized void write(LodVoxelSection section) {
        Column existing = this.readColumn(section.nodeX, section.nodeZ, section.level);
        LodVoxelSection[] sections;
        if (existing != null) {
            sections = existing.sections();
        } else {
            sections = new LodVoxelSection[verticalNodes(section.level)];
            for (int y = 0; y < sections.length; y++) {
                sections[y] = LodVoxelSection.empty(section.nodeX, y, section.nodeZ, section.level,
                        LodVoxelSection.Completeness.PROVISIONAL);
            }
        }
        if (section.nodeY < 0 || section.nodeY >= sections.length) {
            throw new IllegalArgumentException("Section-Y ausserhalb der Welt: " + section.nodeY);
        }
        sections[section.nodeY] = section;
        this.writeColumn(new Column(section.nodeX, section.nodeZ, section.level, sections));
    }

    static int verticalNodes(int level) {
        if (level < 0 || level > LodVoxelSection.MAX_LEVEL) {
            throw new IllegalArgumentException("LOD-Level: " + level);
        }
        return Chunk.HEIGHT / (LodVoxelSection.SIZE << level);
    }

    private RegionFile region(int level, int rx, int rz, boolean create) throws IOException {
        RegionKey key = new RegionKey(level, rx, rz);
        RegionFile open = this.regions.get(key);
        if (open != null) return open;
        File directory = new File(this.root, "l" + level);
        File file = new File(directory, "r." + rx + "." + rz + ".srg");
        if (!file.exists() && !create) return null;
        if (create && !directory.exists() && !directory.mkdirs() && !directory.isDirectory()) {
            throw new IOException("LOD-Verzeichnis nicht erstellbar: " + directory);
        }
        /* LOD ist ein regenerierbarer Cache. Der Writer commitet einen ganzen Drain als
           Batch; dadurch entfallen hunderte fsyncs beim ersten Aufbau. */
        RegionFile region = new RegionFile(file, false);
        this.regions.put(key, region);
        return region;
    }

    public synchronized void flush() {
        for (RegionFile region : this.regions.values()) {
            try { region.flush(); }
            catch (Exception e) { this.logger.warning("Volumen-LOD-Region nicht flushbar", e); }
        }
    }

    @Override
    public synchronized void close() {
        for (RegionFile region : this.regions.values()) {
            try { region.close(); }
            catch (Exception e) { this.logger.warning("Volumen-LOD-Region nicht schliessbar", e); }
        }
        this.regions.clear();
    }
}
