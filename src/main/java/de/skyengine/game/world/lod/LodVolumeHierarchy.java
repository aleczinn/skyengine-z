package de.skyengine.game.world.lod;

import de.skyengine.game.world.block.Blocks;
import de.skyengine.game.world.chunk.Chunk;
import de.skyengine.game.world.chunk.ChunkSection;
import de.skyengine.game.world.generator.WorldGenerator;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-sicherer Speicher fuer die neue 32³-Hierarchie. Analytische Knoten liefern sofort
 * eine vorlaeufige Silhouette; sobald alle acht echten Kinder vorliegen, wird der Elternknoten
 * deterministisch ersetzt. So poppt nie ein Loch auf, und gespeicherte/live Daten gewinnen
 * dauerhaft gegen Generator-Proxies.
 */
public final class LodVolumeHierarchy {

    public record Key(int x, int y, int z, int level) {}

    @FunctionalInterface
    public interface AnalyticSource { void fill(LodVolumeRequest request, LodVolumeWriter writer); }

    private final AnalyticSource analyticSource;
    private final Map<Key, LodVoxelSection> nodes = new ConcurrentHashMap<>();

    public LodVolumeHierarchy(WorldGenerator generator) {
        this(generator::fillLodVolume);
    }

    public LodVolumeHierarchy(AnalyticSource analyticSource) {
        this.analyticSource = analyticSource;
    }

    public LodVoxelSection get(Key key) { return this.nodes.get(key); }

    public LodVoxelSection getOrCreateAnalytic(int nodeX, int nodeY, int nodeZ, int level) {
        Key key = new Key(nodeX, nodeY, nodeZ, level);
        return this.nodes.computeIfAbsent(key, ignored -> {
            LodVoxelSection section = new LodVoxelSection(nodeX, nodeY, nodeZ, level,
                    LodVoxelSection.Completeness.PROVISIONAL);
            this.analyticSource.fill(new LodVolumeRequest(nodeX, nodeY, nodeZ, level), section::set);
            return section;
        });
    }

    /** Erstellt einen exakten L0-Knoten aus einer 32 hohen Section eines geladenen Chunks. */
    public static LodVoxelSection fromChunk(Chunk chunk, int sectionY) {
        if (sectionY < 0 || sectionY >= Chunk.SECTIONS) throw new IllegalArgumentException("Section-Y: " + sectionY);
        LodVoxelSection result = new LodVoxelSection(chunk.chunkX, sectionY, chunk.chunkZ, 0,
                LodVoxelSection.Completeness.CANONICAL);
        int baseY = sectionY << ChunkSection.SHIFT;
        for (int y = 0; y < 32; y++) for (int z = 0; z < 32; z++) for (int x = 0; x < 32; x++) {
            int state = chunk.getBlock(x, baseY + y, z);
            if (state == Blocks.AIR) continue;
            int sky = chunk.light.get(x, baseY + y, z);
            int block = chunk.blockLight.get(x, baseY + y, z);
            result.set(x, y, z, LodVoxel.pack(state, sky, block, block, block, 255,
                    LodVoxel.PROVENANCE_LIVE, 4));
        }
        return result;
    }

    /** Publiziert einen besseren Knoten und baut alle nun vollstaendigen Eltern nach oben neu. */
    public synchronized void publish(LodVoxelSection section) {
        Key key = key(section);
        this.nodes.compute(key, (ignored, old) -> preferred(old, section));
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
            this.nodes.put(key(parent), parent);
            current = parent;
        }
    }

    public int size() { return this.nodes.size(); }

    /** Entfernt eine geaenderte X/Z-Spalte samt aller davon abgeleiteten Eltern. */
    public synchronized void invalidateColumn(int levelZeroX, int levelZeroZ) {
        this.nodes.entrySet().removeIf(entry -> {
            Key key = entry.getKey();
            return key.x == Math.floorDiv(levelZeroX, 1 << key.level)
                    && key.z == Math.floorDiv(levelZeroZ, 1 << key.level);
        });
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
