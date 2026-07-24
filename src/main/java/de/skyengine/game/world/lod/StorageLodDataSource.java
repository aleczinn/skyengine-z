package de.skyengine.game.world.lod;

import de.skyengine.game.world.block.Blocks;
import de.skyengine.game.world.block.state.BlockState;
import de.skyengine.game.world.chunk.Chunk;
import de.skyengine.game.world.chunk.ChunkSection;
import de.skyengine.game.world.save.ChunkSerializer;
import de.skyengine.game.world.save.WorldStorage;
import de.skyengine.utils.logging.LogManager;
import de.skyengine.utils.logging.Logger;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@link LodDataSource} für importierte Welten (Void-Generator): Höhe/Block kommen aus den
 * gespeicherten Region-Snapshots statt aus einer Generator-Funktion. Pro Chunk wird der
 * Payload einmalig deserialisiert und zu zwei Spalten-Heightmaps verdichtet (Oberfläche
 * mit Wasser / fester Boden — gleiche Spaltenscan-Regel wie {@link WorldLodDataSource});
 * Chunks ohne Snapshot liefern Luft auf Höhe 0, die der {@link LodMesher} überspringt.
 *
 * <p>Threadsicher und deterministisch (LOD-Vertrag): die Heightmaps stammen aus den
 * unveränderlichen Dateiinhalten; nach jedem Chunk-Write invalidiert der
 * {@link WorldStorage.ChunkWriteListener} den Cache-Eintrag. Live-Edits geladener Chunks
 * sind fürs LOD unsichtbar, weil deren Zellen die 16-Bit-Chunk-Maske ohnehin clippt —
 * spätestens beim Unload (Save → Write-Listener → Maskenwechsel-Remesh) zieht das LOD nach.
 * Biome-Tints bleiben die Interface-Defaults — der Importer schreibt konstante
 * Plains-Tints mit denselben Werten.
 */
public final class StorageLodDataSource implements LodDataSource {

    /* Deckel des Heightmap-Caches (16 KB je befüllter Eintrag → max ~64 MB); simple
       Vollräumung statt LRU — der Wiederaufbau ist deterministisch und amortisiert. */
    private static final int MAX_CACHED_CHUNKS = 4096;

    /* Marker für Chunks ohne (lesbaren) Snapshot — kein Speicher pro Eintrag. */
    private static final long[] EMPTY = new long[0];

    private static final long AIR_SAMPLE = LodDataSource.pack(Blocks.AIR, 0);
    private static final int COLUMNS = ChunkSection.SIZE * ChunkSection.SIZE;

    private final Logger logger = LogManager.getLogger(StorageLodDataSource.class.getName());
    private final WorldStorage storage;
    /* chunkKey -> long[2*COLUMNS]: [0..COLUMNS) Oberfläche (mit Wasser), danach Boden. */
    private final Map<Long, long[]> cache = new ConcurrentHashMap<>();

    public StorageLodDataSource(WorldStorage storage) {
        this.storage = storage;
        storage.setWriteListener((cx, cz) -> this.cache.remove(key(cx, cz)));
    }

    private static long key(int cx, int cz) {
        return ((long) cx << 32) | (cz & 0xFFFFFFFFL);
    }

    @Override
    public long sampleSurface(int x, int z, int size) {
        return this.sample(x + size / 2, z + size / 2, 0);
    }

    @Override
    public long sampleGround(int x, int z, int size) {
        return this.sample(x + size / 2, z + size / 2, COLUMNS);
    }

    private long sample(int x, int z, int offset) {
        long[] columns = this.columns(x >> ChunkSection.SHIFT, z >> ChunkSection.SHIFT);
        if (columns == EMPTY) return AIR_SAMPLE;
        return columns[offset + (z & ChunkSection.MASK) * ChunkSection.SIZE + (x & ChunkSection.MASK)];
    }

    private long[] columns(int cx, int cz) {
        long[] cached = this.cache.get(key(cx, cz));
        if (cached != null) return cached;
        if (this.cache.size() >= MAX_CACHED_CHUNKS) this.cache.clear();
        return this.cache.computeIfAbsent(key(cx, cz), k -> this.buildColumns(cx, cz));
    }

    /**
     * Liest den Chunk-Snapshot (synchronized Single-Writer im Storage) und verdichtet ihn zu
     * den beiden Heightmaps. Läuft auf den LOD-Workern; der Scratch-Chunk wird nie in die
     * ChunkManager-Map installiert und danach verworfen.
     */
    private long[] buildColumns(int cx, int cz) {
        byte[] payload = this.storage.readChunk(cx, cz);
        if (payload == null) return EMPTY;
        Chunk scratch = new Chunk(cx, cz);
        try {
            ChunkSerializer.deserialize(scratch, payload, null);
        } catch (Exception e) {
            this.logger.warning("LOD-Heightmap: Chunk (" + cx + ", " + cz + ") nicht lesbar", e);
            return EMPTY;
        }

        long[] columns = new long[2 * COLUMNS];
        for (int lz = 0; lz < ChunkSection.SIZE; lz++) {
            for (int lx = 0; lx < ChunkSection.SIZE; lx++) {
                int i = lz * ChunkSection.SIZE + lx;
                long surface = AIR_SAMPLE, ground = AIR_SAMPLE;
                boolean surfaceFound = false;
                scan:
                for (int si = Chunk.SECTIONS - 1; si >= 0; si--) {
                    ChunkSection section = scratch.getSection(si);
                    if (section == null || section.isEmpty()) continue;
                    for (int ly = ChunkSection.SIZE - 1; ly >= 0; ly--) {
                        int id = section.getBlock(lx, ly, lz);
                        if (id == Blocks.AIR) continue;
                        BlockState state = Blocks.getState(id);
                        boolean opaque = state.isOpaqueCube() && !state.isExcludedFromLodSurface();
                        int y = (si << ChunkSection.SHIFT) + ly;
                        if (!surfaceFound && (opaque || state.isFluid())) {
                            surface = LodDataSource.pack(id, y);
                            surfaceFound = true;
                        }
                        if (opaque) {
                            ground = LodDataSource.pack(id, y);
                            break scan;
                        }
                    }
                }
                columns[i] = surface;
                columns[COLUMNS + i] = ground;
            }
        }
        return columns;
    }
}
