package de.skyengine.game.world.generator;

import de.skyengine.game.world.block.Blocks;
import de.skyengine.game.world.chunk.Chunk;
import de.skyengine.game.world.chunk.ChunkSection;
import de.skyengine.game.world.generator.biome.Biome;
import de.skyengine.game.world.generator.biome.Biomes;

import java.util.Random;

/** Generator-Decorator, der nach dem Basisterrain chunkuebergreifende Erzadern einsetzt. */
public final class OreGeneratingWorldGenerator extends WorldGenerator {

    private final WorldGenerator delegate;
    private final OreProfile profile;

    public OreGeneratingWorldGenerator(WorldGenerator delegate, OreProfile profile) {
        super(delegate.getSeed());
        this.delegate = delegate;
        this.profile = profile;
    }

    @Override public int sampleHeight(int x, int z) { return this.delegate.sampleHeight(x, z); }
    @Override public long sampleSurface(int x, int z) { return this.delegate.sampleSurface(x, z); }
    @Override public long sampleGroundSurface(int x, int z) { return this.delegate.sampleGroundSurface(x, z); }
    @Override public LodSurfaces sampleLodSurfaces(int x, int z) { return this.delegate.sampleLodSurfaces(x, z); }
    @Override public void fillLodSurfaces(int x, int z, long[] ground, long[] surface) {
        this.delegate.fillLodSurfaces(x, z, ground, surface);
    }
    @Override public int lodWorldBottomState() { return this.delegate.lodWorldBottomState(); }
    @Override public Biome biomeAt(int x, int z) { return this.delegate.biomeAt(x, z); }
    @Override public int surfaceSolidHeight(int x, int z) { return this.delegate.surfaceSolidHeight(x, z); }
    @Override public int grassTintAt(int x, int z) { return this.delegate.grassTintAt(x, z); }
    @Override public int foliageTintAt(int x, int z) { return this.delegate.foliageTintAt(x, z); }
    @Override public void fillTintCorners(Chunk chunk) { this.delegate.fillTintCorners(chunk); }

    @Override
    public void generate(Chunk chunk) {
        this.delegate.generate(chunk);
        for (int sx = chunk.chunkX - 1; sx <= chunk.chunkX + 1; sx++) {
            for (int sz = chunk.chunkZ - 1; sz <= chunk.chunkZ + 1; sz++) {
                for (OreProfile.Distribution distribution : this.profile.distributions()) {
                    this.placeSourceVeins(chunk, sx, sz, distribution);
                }
            }
        }
    }

    private void placeSourceVeins(Chunk target, int sourceChunkX, int sourceChunkZ,
                                  OreProfile.Distribution distribution) {
        Random random = new Random(seedFor(sourceChunkX, sourceChunkZ, distribution.key()));
        int sourceMinX = sourceChunkX << ChunkSection.SHIFT;
        int sourceMinZ = sourceChunkZ << ChunkSection.SHIFT;
        int range = distribution.maxY() - distribution.minY() + 1;
        for (int attempt = 0; attempt < distribution.attempts(); attempt++) {
            int x = sourceMinX + random.nextInt(ChunkSection.SIZE);
            int y = distribution.minY() + random.nextInt(range);
            int z = sourceMinZ + random.nextInt(ChunkSection.SIZE);
            if (distribution.extremeHillsOnly() && this.delegate.biomeAt(x, z) != Biomes.EXTREME_HILLS) continue;
            for (int i = 0; i < distribution.veinSize(); i++) {
                this.replace(target, x, y, z, distribution.block());
                if ((i & 1) == 0) {
                    this.replace(target, x + random.nextInt(3) - 1,
                            y + random.nextInt(3) - 1, z + random.nextInt(3) - 1,
                            distribution.block());
                }
                x += random.nextInt(3) - 1;
                y = Math.clamp(y + random.nextInt(3) - 1, distribution.minY(), distribution.maxY());
                z += random.nextInt(3) - 1;
            }
        }
    }

    private void replace(Chunk target, int wx, int y, int wz, int ore) {
        if (y <= 0 || y >= Chunk.HEIGHT
                || (wx >> ChunkSection.SHIFT) != target.chunkX
                || (wz >> ChunkSection.SHIFT) != target.chunkZ) return;
        int lx = wx & ChunkSection.MASK, lz = wz & ChunkSection.MASK;
        int old = target.getBlock(lx, y, lz);
        if (old == Blocks.STONE || old == Blocks.GRANITE || old == Blocks.DIORITE || old == Blocks.ANDESITE) {
            target.setBlock(lx, y, lz, ore);
        }
    }

    private long seedFor(int chunkX, int chunkZ, String key) {
        long value = ((long) this.seed << 32) ^ Chunk.key(chunkX, chunkZ) ^ key.hashCode();
        value = (value ^ (value >>> 30)) * 0xBF58476D1CE4E5B9L;
        value = (value ^ (value >>> 27)) * 0x94D049BB133111EBL;
        return value ^ (value >>> 31);
    }
}
