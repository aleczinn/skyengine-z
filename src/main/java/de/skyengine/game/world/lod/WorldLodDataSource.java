package de.skyengine.game.world.lod;

import de.skyengine.game.world.block.Blocks;
import de.skyengine.game.world.block.state.BlockState;
import de.skyengine.game.world.chunk.Chunk;
import de.skyengine.game.world.chunk.ChunkManager;
import de.skyengine.game.world.chunk.ChunkSection;
import de.skyengine.game.world.chunk.ChunkStatus;
import de.skyengine.game.world.generator.WorldGenerator;

/**
 * {@link LodDataSource} mit zweistufiger Quelle:
 * <ul>
 *   <li><b>Stride ≤ 4 (L1/L2, spielernah):</b> Höhe/Block aus echten generierten Chunkdaten
 *       (Spaltenscan am Zellmittel) — erhält echte Oberflächen/Overhang-Kanten und ist die
 *       Basis für spätere Spieleränderungen und gespeicherte Welten.</li>
 *   <li><b>Stride ≥ 8 (L3+) oder Chunk nicht generiert:</b> pure Generator-Funktion —
 *       bei 256 Chunks Sichtweite wäre Chunkgenerierung fürs LOD viel zu teuer.</li>
 * </ul>
 * Chunkdaten und Generator liefern heute identische Oberflächen (gleiche Funktion), die
 * Umschalt-Naht ist daher unsichtbar; sie divergieren erst mit Edits/Features.
 */
public final class WorldLodDataSource implements LodDataSource {

    /* Bis zu dieser Zellgröße (L1/L2) wird aus echten Chunkdaten gesampelt */
    private static final int CHUNK_SAMPLING_MAX_STRIDE = 4;

    private static final long MISS = Long.MIN_VALUE;

    private final ChunkManager chunkManager;
    private final WorldGenerator generator;

    public WorldLodDataSource(ChunkManager chunkManager, WorldGenerator generator) {
        this.chunkManager = chunkManager;
        this.generator = generator;
    }

    @Override
    public long sampleSurface(int x, int z, int size) {
        int cx = x + size / 2, cz = z + size / 2;
        if (size <= CHUNK_SAMPLING_MAX_STRIDE) {
            long sample = this.sampleFromChunk(cx, cz);
            if (sample != MISS) return sample;
        }
        return this.generator.sampleSurface(cx, cz);
    }

    /**
     * Spaltenscan von oben: erster Block, der Fluid oder solide ist (Vegetation/Luft wird
     * übersprungen). Nur Chunks mit vollständigem Terrain (mindestens GENERATED).
     *
     * <p>Liest die Paletten bewusst OHNE Lock (Worker-Thread): einzelne verrissene Samples
     * sind transient und werden beim nächsten Remesh korrigiert; Edits passieren ohnehin nur
     * in READY-Chunks, deren Zellen die LOD-Maske ausblendet.
     */
    private long sampleFromChunk(int x, int z) {
        Chunk chunk = this.chunkManager.getChunk(x >> ChunkSection.SHIFT, z >> ChunkSection.SHIFT);
        if (chunk == null) return MISS;
        if (!chunk.status.isAtLeast(ChunkStatus.GENERATED)) {
            return MISS;
        }

        int lx = x & ChunkSection.MASK, lz = z & ChunkSection.MASK;
        for (int si = Chunk.SECTIONS - 1; si >= 0; si--) {
            ChunkSection section = chunk.getSection(si);
            if (section == null || section.isEmpty()) continue;
            for (int ly = ChunkSection.SIZE - 1; ly >= 0; ly--) {
                int id = section.getBlock(lx, ly, lz);
                if (id == Blocks.AIR) continue;
                BlockState state = Blocks.getState(id);
                if (state.isFluid() || state.isSolid()) {
                    return LodDataSource.pack(id, (si << ChunkSection.SHIFT) + ly);
                }
            }
        }
        return MISS; // komplett leere Spalte — Generator-Fallback
    }
}
