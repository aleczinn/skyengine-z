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
import java.util.UUID;
import java.util.function.Predicate;

/** Dimensionsgebundener, persistenter Index fuer aktive und erloschene Portalrahmen. */
public final class PortalIndex {

    private static final Logger LOGGER = LogManager.getLogger(PortalIndex.class.getName());
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public record Entry(String id, String type, int x, int y, int z, String axis,
                        int width, int height, boolean active) {
        public Direction.Axis portalAxis() { return Direction.Axis.valueOf(axis); }
        public double centerX() { return portalAxis() == Direction.Axis.X ? x + width * 0.5 : x + 0.5; }
        public double centerY() { return y + height * 0.5; }
        public double centerZ() { return portalAxis() == Direction.Axis.Z ? z + width * 0.5 : z + 0.5; }

        Entry withActive(boolean value) {
            return new Entry(id, type, x, y, z, axis, width, height, value);
        }
    }

    private static final class Data {
        int version = 2;
        List<Entry> portals = new ArrayList<>();
    }

    private final File file;
    private final List<Entry> entries = new ArrayList<>();

    public PortalIndex(File dimensionRoot) {
        this.file = new File(dimensionRoot, "portals.json");
        this.load();
    }

    public Entry nearest(Identifier type, int x, int y, int z, int radius) {
        List<Entry> candidates = this.candidates(type, x, y, z, radius, entry -> true);
        return candidates.isEmpty() ? null : candidates.getFirst();
    }

    /** Aktive Kandidaten, geometrisch zentriert und deterministisch nach Entfernung sortiert. */
    public List<Entry> candidates(Identifier type, int x, int y, int z, int radius,
                                  Predicate<Entry> available) {
        double radiusSq = (double) radius * radius;
        return this.entries.stream()
                .filter(Entry::active)
                .filter(entry -> entry.type.equals(type.toString()))
                .filter(available)
                .filter(entry -> {
                    double dx = entry.centerX() - (x + 0.5);
                    double dz = entry.centerZ() - (z + 0.5);
                    return dx * dx + dz * dz <= radiusSq;
                })
                .sorted(Comparator.comparingDouble((Entry entry) -> distanceSquared(entry, x, y, z))
                        .thenComparingInt(Entry::y).thenComparingInt(Entry::x)
                        .thenComparingInt(Entry::z).thenComparing(Entry::id))
                .toList();
    }

    public Entry byId(String id) {
        if (id == null) return null;
        for (Entry entry : this.entries) if (id.equals(entry.id)) return entry;
        return null;
    }

    public Entry containing(Identifier type, int x, int y, int z) {
        for (Entry entry : this.entries) {
            if (entry.type.equals(type.toString()) && contains(entry, x, y, z)) return entry;
        }
        return null;
    }

    /** Registriert einen neuen Rahmen oder reaktiviert exakt denselben unter seiner alten UUID. */
    public Entry add(Identifier type, NetherPortalShape.Shape shape) {
        for (int i = 0; i < this.entries.size(); i++) {
            Entry old = this.entries.get(i);
            if (!sameGeometry(old, type, shape)) continue;
            if (!old.active) {
                old = old.withActive(true);
                this.entries.set(i, old);
                this.save();
            }
            return old;
        }
        Entry entry = new Entry(UUID.randomUUID().toString(), type.toString(), shape.minX(),
                shape.bottomY(), shape.minZ(), shape.axis().name(), shape.width(), shape.height(), true);
        this.entries.add(entry);
        this.save();
        return entry;
    }

    /** Erhaelt Identitaet und Link eines intakten, aber nicht mehr entzuendeten Rahmens. */
    public Entry deactivateContaining(Identifier type, int x, int y, int z) {
        for (int i = 0; i < this.entries.size(); i++) {
            Entry old = this.entries.get(i);
            if (!old.type.equals(type.toString()) || !contains(old, x, y, z)) continue;
            if (old.active) {
                old = old.withActive(false);
                this.entries.set(i, old);
                this.save();
            }
            return old;
        }
        return null;
    }

    public Entry remove(Entry entry) {
        if (entry != null && this.entries.remove(entry)) {
            this.save();
            return entry;
        }
        return null;
    }

    public Entry removeContaining(Identifier type, int x, int y, int z) {
        for (int i = 0; i < this.entries.size(); i++) {
            Entry entry = this.entries.get(i);
            if (!entry.type.equals(type.toString()) || !contains(entry, x, y, z)) continue;
            this.entries.remove(i);
            this.save();
            return entry;
        }
        return null;
    }

    private static boolean sameGeometry(Entry entry, Identifier type, NetherPortalShape.Shape shape) {
        return entry.type.equals(type.toString()) && entry.x == shape.minX()
                && entry.y == shape.bottomY() && entry.z == shape.minZ()
                && entry.axis.equals(shape.axis().name()) && entry.width == shape.width()
                && entry.height == shape.height();
    }

    private static boolean contains(Entry entry, int x, int y, int z) {
        if (y < entry.y || y >= entry.y + entry.height) return false;
        return entry.portalAxis() == Direction.Axis.X
                ? z == entry.z && x >= entry.x && x < entry.x + entry.width
                : x == entry.x && z >= entry.z && z < entry.z + entry.width;
    }

    private static double distanceSquared(Entry entry, int x, int y, int z) {
        double dx = entry.centerX() - (x + 0.5);
        double dy = entry.centerY() - (y + 0.5);
        double dz = entry.centerZ() - (z + 0.5);
        return dx * dx + dy * dy + dz * dz;
    }

    private void load() {
        if (!this.file.isFile()) return;
        try {
            Data data = GSON.fromJson(Files.readString(this.file.toPath()), Data.class);
            if (data == null || data.portals == null) return;
            boolean migrated = false;
            if (data.version == 1) {
                for (Entry old : data.portals) {
                    this.entries.add(new Entry(UUID.randomUUID().toString(), canonicalType(old.type), old.x, old.y,
                            old.z, old.axis, old.width, old.height, true));
                }
                migrated = true;
            } else if (data.version == 2) {
                for (Entry entry : data.portals) {
                    if (entry.id == null || entry.id.isBlank()) continue;
                    String type = canonicalType(entry.type);
                    this.entries.add(type.equals(entry.type) ? entry : new Entry(entry.id, type,
                            entry.x, entry.y, entry.z, entry.axis, entry.width, entry.height, entry.active));
                    migrated |= !type.equals(entry.type);
                }
            }
            if (migrated) this.save();
        } catch (Exception e) {
            LOGGER.warning("Portalindex konnte nicht geladen werden: " + this.file + " (" + e.getMessage() + ")");
        }
    }

    private static String canonicalType(String type) {
        return Identifier.of(type).toString();
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
