package de.skyengine.game.world.generator.feature;

import de.skyengine.game.world.block.Blocks;
import de.skyengine.game.world.chunk.Chunk;
import de.skyengine.game.world.chunk.ChunkSection;
import de.skyengine.game.world.generator.WorldGenerator;
import de.skyengine.game.world.generator.SurfaceSample;
import de.skyengine.game.world.generator.biome.Biome;
import de.skyengine.game.world.structure.StructureTemplate;

import java.util.Random;

/**
 * Kontext + scheiben-filternder Writer für EINEN (Quell-Chunk, Ziel-Chunk, Feature)-Durchlauf:
 * Das Feature rechnet in Weltkoordinaten, geschrieben wird aber nur, was im Ziel-Chunk liegt —
 * die übrigen Scheiben desselben Features schreiben die jeweils anderen Chunks selbst.
 *
 * <p>Schreibt direkt via {@link Chunk#setBlock} (keine Dirty-Flags, keine Locks): der
 * Ziel-Chunk gehört während DECORATING exklusiv dem dekorierenden Worker, genau wie
 * während der Terrain-Generierung.
 */
public final class FeaturePlacer implements FeatureContext {

    private final Chunk target;
    private final int sourceMinX, sourceMinZ;
    private final Random random;
    private final WorldGenerator generator;

    FeaturePlacer(Chunk target, int sourceChunkX, int sourceChunkZ, Random random, WorldGenerator generator) {
        this.target = target;
        this.sourceMinX = sourceChunkX << ChunkSection.SHIFT;
        this.sourceMinZ = sourceChunkZ << ChunkSection.SHIFT;
        this.random = random;
        this.generator = generator;
    }

    /** Pro (Quell-Chunk, Feature) deterministisch geseedeter RNG. */
    public Random random() {
        return random;
    }

    /** Welt-X-Ursprung des Quell-Chunks; Platzierung wählt Positionen in [min, min+32). */
    public int sourceMinX() {
        return sourceMinX;
    }

    /** Welt-Z-Ursprung des Quell-Chunks. */
    public int sourceMinZ() {
        return sourceMinZ;
    }

    /**
     * Pure echte Terrainoberkante an (wx, wz) — auch über Chunk-Grenzen hinweg sicher.
     * Nutzt {@link WorldGenerator#surfaceSolidHeight}, damit Features auch bei 3D-verformtem
     * Terrain auf dem realen Boden stehen (nicht auf der 2D-Heightmap schweben).
     */
    public int surfaceHeight(int wx, int wz) {
        return this.generator.surfaceSolidHeight(wx, wz);
    }

    /** Purer Oberflächen-Block an (wx, wz) (im Ozean: Wasser am Meeresspiegel). */
    public int surfaceBlock(int wx, int wz) {
        return SurfaceSample.block(this.generator.sampleSurface(wx, wz));
    }

    /** Pures Biom an (wx, wz) — Basis für biome-abhängige Feature-Entscheidungen. */
    public Biome biome(int wx, int wz) {
        return this.generator.biomeAt(wx, wz);
    }

    /** Schreibt den Block, wenn (wx, wy, wz) im Ziel-Chunk liegt — sonst stilles No-Op. */
    public void set(int wx, int wy, int wz, int block) {
        if (!this.inTarget(wx, wz)) return;
        this.target.setBlock(wx & ChunkSection.MASK, wy, wz & ChunkSection.MASK, block);
    }

    /** Wie {@link #set}, aber nur in Luft — z.B. Blätter, die Stamm/Terrain nicht überschreiben. */
    public void setIfAir(int wx, int wy, int wz, int block) {
        if (!this.inTarget(wx, wz)) return;
        int lx = wx & ChunkSection.MASK, lz = wz & ChunkSection.MASK;
        if (this.target.getBlock(lx, wy, lz) != Blocks.AIR) return;
        this.target.setBlock(lx, wy, lz, block);
    }

    @Override
    public void setStructureCell(int wx, int wy, int wz, int block, boolean ifAir,
                                 StructureTemplate.BlockEntitySnapshot blockEntity) {
        if (!this.inTarget(wx, wz) || wy < 0 || wy >= Chunk.HEIGHT) return;
        int lx = wx & ChunkSection.MASK, lz = wz & ChunkSection.MASK;
        if (ifAir && this.target.getBlock(lx, wy, lz) != Blocks.AIR) return;
        this.target.setBlock(lx, wy, lz, block);
        if (Blocks.getState(block).getBlock().getBlockEntityType() != null) {
            this.target.queueStructureBlockEntity(lx, wy, lz, blockEntity);
        }
    }

    private boolean inTarget(int wx, int wz) {
        return (wx >> ChunkSection.SHIFT) == this.target.chunkX
                && (wz >> ChunkSection.SHIFT) == this.target.chunkZ;
    }
}
