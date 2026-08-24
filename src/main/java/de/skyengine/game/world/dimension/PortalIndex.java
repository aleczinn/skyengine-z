package de.skyengine.game.world.dimension;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import de.skyengine.game.world.block.Direction;
import de.skyengine.game.world.block.Identifier;
import de.skyengine.utils.logging.LogManager;
import de.skyengine.utils.logging.Logger;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Kleiner dimensionsgebundener Index fuer Portalrahmen in nicht geladenen Chunks. */
public final class PortalIndex {

    private static final Logger LOGGER = LogManager.getLogger(PortalIndex.class.getName());
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public record Entry(String type, int x, int y, int z, String axis, int width, int height) {
        public Direction.Axis portalAxis() { return Direction.Axis.valueOf(axis); }
        public double centerX() { return portalAxis() == Direction.Axis.X ? x + width * 0.5 : x + 0.5; }
        public double centerY() { return y + height * 0.5; }
        public double centerZ() { return portalAxis() == Direction.Axis.Z ? z + width * 0.5 : z + 0.5; }
    }

    private static final class Data {
        int version = 1;
        List<Entry> portals = new ArrayList<>();
    }

    private final File file;
    private final List<Entry> entries = new ArrayList<>();

    public PortalIndex(File dimensionRoot) {
        this.file = new File(dimensionRoot, "portals.json");
        this.load();
    }

    public Entry nearest(Identifier type, int x, int y, int z, int radius) {
        long radiusSq = (long) radius * radius;
        return this.entries.stream()
                .filter(entry -> entry.type.equals(type.toString()))
                .filter(entry -> {
                    long dx = entry.x - x, dz = entry.z - z;
                    return dx * dx + dz * dz <= radiusSq;
                })
                .min(Comparator.comparingLong((Entry entry) -> distanceSquared(entry, x, y, z))
                        .thenComparingInt(Entry::y).thenComparingInt(Entry::x).thenComparingInt(Entry::z))
                .orElse(null);
    }

    public void add(Identifier type, NetherPortalShape.Shape shape) {
        Entry entry = new Entry(type.toString(), shape.minX(), shape.bottomY(), shape.minZ(),
                shape.axis().name(), shape.width(), shape.height());
        this.entries.removeIf(old -> old.type.equals(entry.type) && old.x == entry.x
                && old.y == entry.y && old.z == entry.z);
        this.entries.add(entry);
        this.save();
    }

    public void remove(Entry entry) {
        if (entry != null && this.entries.remove(entry)) this.save();
    }

    public void removeContaining(Identifier type, int x, int y, int z) {
        boolean removed = this.entries.removeIf(entry -> entry.type.equals(type.toString())
                && contains(entry, x, y, z));
        if (removed) this.save();
    }

    private static boolean contains(Entry entry, int x, int y, int z) {
        if (y < entry.y || y >= entry.y + entry.height) return false;
        return entry.portalAxis() == Direction.Axis.X
                ? z == entry.z && x >= entry.x && x < entry.x + entry.width
                : x == entry.x && z >= entry.z && z < entry.z + entry.width;
    }

    private static long distanceSquared(Entry entry, int x, int y, int z) {
        long dx = entry.x - x, dy = entry.y - y, dz = entry.z - z;
        return dx * dx + dy * dy + dz * dz;
    }

    private void load() {
        if (!this.file.isFile()) return;
        try {
            Data data = GSON.fromJson(Files.readString(this.file.toPath()), Data.class);
            if (data != null && data.version == 1 && data.portals != null) this.entries.addAll(data.portals);
        } catch (Exception e) {
            LOGGER.warning("Portalindex konnte nicht geladen werden: " + this.file + " (" + e.getMessage() + ")");
        }
    }

    private void save() {
        try {
            Files.createDirectories(this.file.toPath().getParent());
            Data data = new Data();
            data.portals = new ArrayList<>(this.entries);
            File temp = new File(this.file.getParentFile(), this.file.getName() + ".tmp");
            Files.writeString(temp.toPath(), GSON.toJson(data));
            try {
                Files.move(temp.toPath(), this.file.toPath(), StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
                Files.move(temp.toPath(), this.file.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception e) {
            LOGGER.warning("Portalindex konnte nicht gespeichert werden: " + this.file + " (" + e.getMessage() + ")");
        }
    }
}
