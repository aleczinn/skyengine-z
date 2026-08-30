package de.skyengine.game.world.lod;

import de.skyengine.game.world.block.Blocks;
import de.skyengine.game.world.chunk.Chunk;
import de.skyengine.game.world.chunk.ChunkSection;
import de.skyengine.game.world.generator.WorldGenerator;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Thread-sicherer Speicher fuer die neue 32³-Hierarchie. Analytische Knoten liefern sofort
 * eine vorlaeufige Silhouette; sobald alle acht echten Kinder vorliegen, wird der Elternknoten
 * deterministisch ersetzt. So poppt nie ein Loch auf, und gespeicherte/live Daten gewinnen
 * dauerhaft gegen Generator-Proxies.
 */
public final class LodVolumeHierarchy {

    public record Key(int x, int y, int z, int level) {}
    private record Access(Key key, long stamp) {}
    @FunctionalInterface
    public interface AnalyticSource { void fill(LodVolumeRequest request, LodVolumeWriter writer); }

    private final AnalyticSource analyticSource;
    private final Map<Key, LodVoxelSection> nodes = new ConcurrentHashMap<>();
    private final Map<Key, Long> accessStamps = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<Access> accessOrder = new ConcurrentLinkedQueue<>();
    private final AtomicLong nextContentVersion = new AtomicLong();
    private final Map<Key, Long> contentVersions = new ConcurrentHashMap<>();
    /* Die Eigenschaft gehoert zum Inhalt und ueberlebt CPU-Eviction. Sonst springt die
       Nachbarrevision beim Auslagern kanonischer Knoten zwischen Version und null. */
    private final Set<Key> canonicalKeys = ConcurrentHashMap.newKeySet();
    private final AtomicLong residentBytes = new AtomicLong();
    /* 384 MiB lassen innerhalb des 512-MiB-Gesamtziels Platz fuer zwei Builder, Meshresultate
       und Queue-Metadaten. Der Wert ist absichtlich unabhaengig von sehr grossen JVM-Heaps. */
    private static final long MAX_RESIDENT_BYTES = Math.clamp(
            Runtime.getRuntime().maxMemory() / 8L, 128L << 20, 384L << 20);

    public LodVolumeHierarchy(WorldGenerator generator) {
        this(generator::fillLodVolume);
    }

    public LodVolumeHierarchy(AnalyticSource analyticSource) {
        this.analyticSource = analyticSource;
    }

    public LodVoxelSection get(Key key) {
        LodVoxelSection section = this.nodes.get(key);
        if (section != null) this.touch(key);
        return section;
    }

    public LodVoxelSection getOrCreateAnalytic(int nodeX, int nodeY, int nodeZ, int level) {
        Key key = new Key(nodeX, nodeY, nodeZ, level);
        LodVoxelSection existing = this.nodes.get(key);
        if (existing != null) {
            this.touch(key);
            return existing;
        }
        /* computeIfAbsent verhindert, dass zwei Nachbar-Meshjobs denselben gemeinsamen
           Halo-Knoten gleichzeitig als je 256-KiB-Rohsection aufbauen. */
        LodVoxelSection result = this.nodes.computeIfAbsent(key, ignored -> {
            LodVoxelSection created = new LodVoxelSection(nodeX, nodeY, nodeZ, level,
                    LodVoxelSection.Completeness.PROVISIONAL);
            this.analyticSource.fill(new LodVolumeRequest(nodeX, nodeY, nodeZ, level), created::set);
            created.compact();
            this.residentBytes.addAndGet(created.estimatedBytes());
            this.markChanged(key);
            return created;
        });
        this.evictIfNeeded();
        this.touch(key);
        return result;
    }

    /** Erstellt einen exakten L0-Knoten aus einer 32 hohen Section eines geladenen Chunks. */
    public static LodVoxelSection fromChunk(Chunk chunk, int sectionY) {
        if (sectionY < 0 || sectionY >= Chunk.SECTIONS) throw new IllegalArgumentException("Section-Y: " + sectionY);
        ChunkSection source = chunk.getSection(sectionY);
        /* In einer normalen Welt sind die meisten der 16 vertikalen Sections reine Luft.
           Der fruehere Pfad lief trotzdem fuer jede davon durch 32^3 getBlock-/Light-Lookups
           und verursachte so den Grossteil der Start-CPU. Der leere kanonische Knoten bleibt
           wichtig fuer den 2x2x2-Reducer, kostet kompakt aber nur einen Paletteneintrag. */
        if (source == null || source.isEmpty()) {
            return LodVoxelSection.empty(chunk.chunkX, sectionY, chunk.chunkZ, 0,
                    LodVoxelSection.Completeness.CANONICAL);
        }
        LodVoxelSection result = new LodVoxelSection(chunk.chunkX, sectionY, chunk.chunkZ, 0,
                LodVoxelSection.Completeness.CANONICAL);
        int baseY = sectionY << ChunkSection.SHIFT;
        for (int y = 0; y < 32; y++) for (int z = 0; z < 32; z++) for (int x = 0; x < 32; x++) {
            int state = source.getBlock(x, y, z);
            if (state == Blocks.AIR) continue;
            state = LodBlockRules.simplifyVolume(state);
            if (state == Blocks.AIR) continue;
            int sky = chunk.light.get(x, baseY + y, z);
            int block = chunk.blockLight.get(x, baseY + y, z);
            result.set(x, y, z, LodVoxel.pack(state, sky, block, block, block, 255,
                    LodVoxel.PROVENANCE_LIVE, LodBlockRules.volumeImportance(state)));
        }
        result.compact();
        return result;
    }

    /** Publiziert einen besseren Knoten und baut alle nun vollstaendigen Eltern nach oben neu. */
    public synchronized void publish(LodVoxelSection section) {
        this.publish(section, false);
    }

    /** Stellt einen persistenten Knoten wieder her. Reine CPU-Residency ist keine
        Inhaltsaenderung und invalidiert deshalb kein weiterhin gueltiges GPU-Mesh. */
    public synchronized void restore(LodVoxelSection section) {
        this.publish(section, true);
    }

    private void publish(LodVoxelSection section, boolean restoring) {
        Key key = key(section);
        LodVoxelSection old = this.nodes.get(key);
        LodVoxelSection chosen = preferred(old, section);
        if (chosen != old) {
            boolean knownSameResidency = restoring && old == null
                    && this.contentVersions.containsKey(key)
                    && this.canonicalKeys.contains(key)
                    == (chosen.completeness() == LodVoxelSection.Completeness.CANONICAL);
            this.nodes.put(key, chosen);
            this.residentBytes.addAndGet(chosen.estimatedBytes() - (old == null ? 0 : old.estimatedBytes()));
            if (chosen.completeness() == LodVoxelSection.Completeness.CANONICAL) {
                this.canonicalKeys.add(key);
            } else {
                this.canonicalKeys.remove(key);
            }
            if (!knownSameResidency) this.markChanged(key);
        }
        this.touch(key);
        LodVoxelSection current = this.nodes.get(key);
        while (current != null && current.level < LodVoxelSection.MAX_LEVEL) {
            int px = Math.floorDiv(current.nodeX, 2);
            int py = Math.floorDiv(current.nodeY, 2);
            int pz = Math.floorDiv(current.nodeZ, 2);
            LodVoxelSection[] children = new LodVoxelSection[8];
            boolean complete = true;
            for (int i = 0; i < 8; i++) {
                int cx = px * 2 + (i & 1), cz = pz * 2 + (i >>> 1 & 1), cy = py * 2 + (i >>> 2 & 1);
                children[i] = this.nodes.get(new Key(cx, cy, cz, current.level));
                complete &= children[i] != null
                        && children[i].completeness() == LodVoxelSection.Completeness.CANONICAL;
            }
            if (!complete) break;
            LodVoxelSection parent = LodVoxelReducer.reduce(children);
            Key parentKey = key(parent);
            LodVoxelSection oldParent = this.nodes.put(parentKey, parent);
            this.residentBytes.addAndGet(parent.estimatedBytes()
                    - (oldParent == null ? 0 : oldParent.estimatedBytes()));
            this.canonicalKeys.add(parentKey);
            this.markChanged(parentKey);
            this.touch(parentKey);
            current = parent;
        }
        this.evictIfNeeded();
    }

    public int size() { return this.nodes.size(); }

    /** Selten abgefragte Debugzahl; O(n), daher nicht fuer den Tick-Hotpath gedacht. */
    public long estimatedBytes() {
        return Math.max(0L, this.residentBytes.get());
    }

    /** Entfernt eine geaenderte X/Z-Spalte samt aller davon abgeleiteten Eltern. */
    public synchronized void invalidateColumn(int levelZeroX, int levelZeroZ) {
        this.nodes.entrySet().removeIf(entry -> {
            Key key = entry.getKey();
            boolean remove = key.x == Math.floorDiv(levelZeroX, 1 << key.level)
                    && key.z == Math.floorDiv(levelZeroZ, 1 << key.level);
            if (remove) {
                this.residentBytes.addAndGet(-entry.getValue().estimatedBytes());
                this.accessStamps.remove(key);
                this.canonicalKeys.remove(key);
                this.markChanged(key);
            }
            return remove;
        });
    }

    /** Persistente Inhaltsversion eines Knotens. CPU-Eviction ändert sie bewusst nicht, damit
        ein weiterhin gültiges GPU-Mesh bei Kamerabewegung keinen Neubau anfordert. */
    public long contentVersion(Key key) {
        return this.contentVersions.getOrDefault(key, 0L);
    }

    /** Generator-Proxies sind durch das analytische Halo bereits stabil. Nur kanonische
        Nachbarn duerfen ein bestehendes Mesh wegen echter Chunkdaten invalidieren. */
    public long canonicalContentVersion(Key key) {
        return this.canonicalKeys.contains(key) ? this.contentVersions.getOrDefault(key, 0L) : 0L;
    }

    private void markChanged(Key key) {
        this.contentVersions.put(key, this.nextContentVersion.incrementAndGet());
    }

    private void touch(Key key) {
        long now = System.nanoTime();
        while (true) {
            Long previous = this.accessStamps.get(key);
            /* Sichtbare Knoten werden sehr oft gelesen. Eine neue Queue-Zelle pro Zugriff
               wuerde den LRU selbst zum Speicherleck machen; 50 ms reichen fuer Eviction. */
            if (previous != null && now - previous < 50_000_000L) return;
            boolean updated = previous == null
                    ? this.accessStamps.putIfAbsent(key, now) == null
                    : this.accessStamps.replace(key, previous, now);
            if (!updated) continue;
            this.accessOrder.offer(new Access(key, now));
            return;
        }
    }

    private synchronized void evictIfNeeded() {
        while (this.residentBytes.get() > MAX_RESIDENT_BYTES) {
            Access oldest = this.accessOrder.poll();
            if (oldest == null) return;
            if (!this.accessStamps.remove(oldest.key, oldest.stamp)) continue;
            LodVoxelSection removed = this.nodes.remove(oldest.key);
            if (removed == null) continue;
            /* Die Inhaltsrevision gehoert zum persistenten Knoten, nicht zu seiner CPU-
               Residency. Sonst interpretiert ein weiterhin gueltiges GPU-Mesh jede
               Speicher-Eviction als Inhaltsaenderung und baut bei Kamerabewegung neu. */
            this.residentBytes.addAndGet(-removed.estimatedBytes());
        }
    }

    private static LodVoxelSection preferred(LodVoxelSection old, LodVoxelSection fresh) {
        if (old == null) return fresh;
        if (old.completeness() == LodVoxelSection.Completeness.CANONICAL
                && fresh.completeness() != LodVoxelSection.Completeness.CANONICAL) return old;
        return fresh;
    }

    private static Key key(LodVoxelSection section) {
        return new Key(section.nodeX, section.nodeY, section.nodeZ, section.level);
    }
}
