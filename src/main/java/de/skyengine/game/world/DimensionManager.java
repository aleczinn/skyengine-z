package de.skyengine.game.world;

import de.skyengine.audio.SoundManager;
import de.skyengine.core.io.IDisposable;
import de.skyengine.game.world.block.Identifier;
import de.skyengine.game.world.chunk.WorldWorkerPool;
import de.skyengine.game.world.dimension.PortalLinks;
import de.skyengine.game.world.dimension.WorldgenRegistries;
import de.skyengine.game.world.save.LevelData;
import de.skyengine.game.world.structure.StructureTemplateManager;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.LongSupplier;

/** Lazy-Lebenszyklus aller Dimensionen eines geoeffneten Savegames. */
public final class DimensionManager implements IDisposable {

    public enum TicketType { PLAYER, PORTAL_TRANSFER, FORCED }

    private static final long DEFAULT_UNLOAD_DELAY_NANOS = 30_000_000_000L;

    private static final class Loaded {
        final Dimension dimension;
        final Map<TicketKey, Integer> tickets = new LinkedHashMap<>();
        long idleSince = Long.MIN_VALUE;

        Loaded(Dimension dimension) {
            this.dimension = dimension;
        }

        int ticketCount() {
            int count = 0;
            for (int value : this.tickets.values()) count += value;
            return count;
        }
    }

    private record TicketKey(TicketType type, Object owner) {}

    public final class DimensionTicket implements AutoCloseable {
        private final Identifier id;
        private final TicketKey key;
        private boolean closed;

        private DimensionTicket(Identifier id, TicketKey key) {
            this.id = id;
            this.key = key;
        }

        public Dimension dimension() {
            Loaded loaded = dimensions.get(this.id);
            if (this.closed || loaded == null) throw new IllegalStateException("Dimension-Ticket ist geschlossen");
            return loaded.dimension;
        }

        @Override
        public void close() {
            if (this.closed) return;
            this.closed = true;
            release(this.id, this.key);
        }
    }

    private final String dirName;
    private final LevelData level;
    private final File saveRoot;
    private final WorldWorkerPool workers;
    private final PortalLinks portalLinks;
    private final SoundManager soundManager;
    private final de.skyengine.game.world.structure.StructureTemplateManager.Snapshot structures;
    private final LongSupplier clock;
    private final long unloadDelayNanos;
    private final Map<Identifier, Loaded> dimensions = new LinkedHashMap<>();

    DimensionManager(String dirName, LevelData level, File saveRoot, WorldWorkerPool workers,
                     PortalLinks portalLinks, SoundManager soundManager) {
        this(dirName, level, saveRoot, workers, portalLinks, soundManager,
                System::nanoTime, DEFAULT_UNLOAD_DELAY_NANOS, null);
    }

    DimensionManager(String dirName, LevelData level, File saveRoot, WorldWorkerPool workers,
                     PortalLinks portalLinks, SoundManager soundManager,
                     StructureTemplateManager.Snapshot structures) {
        this(dirName, level, saveRoot, workers, portalLinks, soundManager, System::nanoTime, DEFAULT_UNLOAD_DELAY_NANOS, structures);
    }

    DimensionManager(String dirName, LevelData level, File saveRoot, WorldWorkerPool workers,
                     PortalLinks portalLinks, SoundManager soundManager,
                     LongSupplier clock, long unloadDelayNanos) {
        this(dirName, level, saveRoot, workers, portalLinks, soundManager, clock, unloadDelayNanos, null);
    }

    DimensionManager(String dirName, LevelData level, File saveRoot, WorldWorkerPool workers,
                     PortalLinks portalLinks, SoundManager soundManager,
                     LongSupplier clock, long unloadDelayNanos,
                     StructureTemplateManager.Snapshot structures) {
        this.dirName = dirName;
        this.level = level;
        this.saveRoot = saveRoot;
        this.workers = workers;
        this.portalLinks = portalLinks;
        this.soundManager = soundManager;
        this.structures = structures;
        this.clock = clock;
        this.unloadDelayNanos = unloadDelayNanos;
    }

    public DimensionTicket acquire(Identifier id, TicketType type, Object owner) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(owner, "owner");
        if (WorldgenRegistries.DIMENSIONS.get(id) == null) {
            throw new IllegalArgumentException("Unbekannte Dimension: " + id);
        }
        Loaded loaded = this.dimensions.computeIfAbsent(id, this::load);
        TicketKey key = new TicketKey(type, owner);
        loaded.tickets.merge(key, 1, Integer::sum);
        loaded.idleSince = Long.MIN_VALUE;
        return new DimensionTicket(id, key);
    }

    public Dimension getLoaded(Identifier id) {
        Loaded loaded = this.dimensions.get(id);
        return loaded == null ? null : loaded.dimension;
    }

    public int loadedCount() {
        return this.dimensions.size();
    }

    public int saveModifiedChunks(boolean materializeFalling) {
        int chunks = 0;
        for (Loaded loaded : this.dimensions.values()) {
            loaded.dimension.saveRuntimeState();
            chunks += loaded.dimension.saveModifiedChunks(materializeFalling);
        }
        return chunks;
    }

    public boolean hasPendingSaves() {
        for (Loaded loaded : this.dimensions.values()) {
            if (loaded.dimension.hasPendingSaves()) return true;
        }
        return false;
    }

    public int tickLifecycle() {
        long now = this.clock.getAsLong();
        int unloaded = 0;
        for (Map.Entry<Identifier, Loaded> entry : new ArrayList<>(this.dimensions.entrySet())) {
            Loaded loaded = entry.getValue();
            if (loaded.ticketCount() > 0) {
                loaded.idleSince = Long.MIN_VALUE;
                continue;
            }
            if (loaded.idleSince == Long.MIN_VALUE) {
                loaded.idleSince = now;
                continue;
            }
            if (now - loaded.idleSince < this.unloadDelayNanos) continue;
            loaded.dimension.saveRuntimeState();
            loaded.dimension.saveModifiedChunks(true);
            loaded.dimension.dispose();
            this.dimensions.remove(entry.getKey());
            unloaded++;
        }
        return unloaded;
    }

    private Loaded load(Identifier id) {
        Dimension dimension = new Dimension(this.dirName, this.level, id, this.saveRoot,
                this.workers, this.portalLinks, this.structures);
        dimension.setSoundManager(this.soundManager);
        dimension.init();
        return new Loaded(dimension);
    }

    private void release(Identifier id, TicketKey key) {
        Loaded loaded = this.dimensions.get(id);
        if (loaded == null) return;
        Integer count = loaded.tickets.get(key);
        if (count == null) return;
        if (count <= 1) loaded.tickets.remove(key);
        else loaded.tickets.put(key, count - 1);
        if (loaded.ticketCount() == 0) loaded.idleSince = this.clock.getAsLong();
    }

    @Override
    public void dispose() {
        for (Loaded loaded : new ArrayList<>(this.dimensions.values())) {
            loaded.dimension.saveRuntimeState();
            loaded.dimension.saveModifiedChunks(true);
            loaded.dimension.dispose();
        }
        this.dimensions.clear();
    }
}
