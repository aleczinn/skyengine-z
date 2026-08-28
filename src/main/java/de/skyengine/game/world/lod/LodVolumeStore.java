package de.skyengine.game.world.lod;

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
 * Versionierter Cache unter {@code lod/volumes-v1}. Pro Level und Knoten-Y enthalten normale
 * 16x16-{@link RegionFile}s die X/Z-Knoten. Damit bleiben Eintraege unter dem Sektorlimit,
 * waehrend saemtliche vertikalen Knoten persistiert werden koennen. Alte Spalten-LOD-Dateien
 * werden weder gelesen noch geloescht.
 */
public final class LodVolumeStore implements AutoCloseable {

    private static final int MAGIC = 0x4C563031; // LV01

    private record RegionKey(int level, int y, int rx, int rz) {}

    private final Logger logger = LogManager.getLogger(LodVolumeStore.class.getName());
    private final File root;
    private final int fingerprint;
    private final Map<RegionKey, RegionFile> regions = new HashMap<>();

    public LodVolumeStore(File lodDirectory, int fingerprint) {
        this.root = new File(lodDirectory, "volumes-v1");
        this.fingerprint = fingerprint;
    }

    public synchronized LodVoxelSection read(int nodeX, int nodeY, int nodeZ, int level) {
        try {
            RegionFile region = region(level, nodeY, Math.floorDiv(nodeX, 16),
                    Math.floorDiv(nodeZ, 16), false);
            if (region == null) return null;
            byte[] payload = region.read(Math.floorMod(nodeX, 16), Math.floorMod(nodeZ, 16));
            if (payload == null) return null;
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload));
            if (in.readInt() != MAGIC || in.readInt() != this.fingerprint
                    || in.readInt() != nodeX || in.readInt() != nodeY || in.readInt() != nodeZ
                    || in.readUnsignedByte() != level) return null;
            int completeness = in.readUnsignedByte();
            int count = in.readInt();
            if (count != LodVoxelSection.VOLUME) return null;
            long[] voxels = new long[count];
            for (int i = 0; i < count; i++) voxels[i] = in.readLong();
            if (in.available() != 0) return null;
            return new LodVoxelSection(nodeX, nodeY, nodeZ, level,
                    completeness == 1 ? LodVoxelSection.Completeness.CANONICAL
                            : LodVoxelSection.Completeness.PROVISIONAL, voxels);
        } catch (Exception e) {
            this.logger.warning("Volumen-LOD-Knoten nicht lesbar: L" + level + " ("
                    + nodeX + ", " + nodeY + ", " + nodeZ + ")", e);
            return null;
        }
    }

    public synchronized void write(LodVoxelSection section) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(16 + LodVoxelSection.VOLUME * 8);
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeInt(MAGIC);
            out.writeInt(this.fingerprint);
            out.writeInt(section.nodeX);
            out.writeInt(section.nodeY);
            out.writeInt(section.nodeZ);
            out.writeByte(section.level);
            out.writeByte(section.completeness() == LodVoxelSection.Completeness.CANONICAL ? 1 : 0);
            out.writeInt(LodVoxelSection.VOLUME);
            for (long voxel : section.voxels()) out.writeLong(voxel);
            out.flush();
            RegionFile region = region(section.level, section.nodeY,
                    Math.floorDiv(section.nodeX, 16), Math.floorDiv(section.nodeZ, 16), true);
            region.write(Math.floorMod(section.nodeX, 16), Math.floorMod(section.nodeZ, 16), bytes.toByteArray());
        } catch (Exception e) {
            this.logger.warning("Volumen-LOD-Knoten nicht schreibbar: L" + section.level + " ("
                    + section.nodeX + ", " + section.nodeY + ", " + section.nodeZ + ")", e);
        }
    }

    private RegionFile region(int level, int y, int rx, int rz, boolean create) throws IOException {
        RegionKey key = new RegionKey(level, y, rx, rz);
        RegionFile open = this.regions.get(key);
        if (open != null) return open;
        File directory = new File(new File(this.root, "l" + level), "y" + y);
        File file = new File(directory, "r." + rx + "." + rz + ".srg");
        if (!file.exists() && !create) return null;
        if (create && !directory.exists() && !directory.mkdirs() && !directory.isDirectory()) {
            throw new IOException("LOD-Verzeichnis nicht erstellbar: " + directory);
        }
        RegionFile region = new RegionFile(file);
        this.regions.put(key, region);
        return region;
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
