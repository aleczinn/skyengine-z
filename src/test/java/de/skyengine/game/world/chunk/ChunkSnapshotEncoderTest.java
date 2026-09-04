package de.skyengine.game.world.chunk;

import de.skyengine.client.network.LegacyChunkSnapshotDecoder;
import de.skyengine.game.world.block.Blocks;
import de.skyengine.game.world.generator.WorldGenerator;
import de.skyengine.game.world.generator.biome.Biome;
import de.skyengine.game.world.generator.biome.Biomes;
import de.skyengine.shared.world.ChunkColumnSnapshot;
import de.skyengine.shared.world.LightPlane;
import de.skyengine.test.BlocksTestBootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class ChunkSnapshotEncoderTest {
    @BeforeAll static void bootstrap() {
        BlocksTestBootstrap.ensureBootstrapped();
    }

    @Test void authoritativeChunkRoundTripsThroughTransportSnapshot() throws Exception {
        Chunk source = new Chunk(2, -3);
        source.setBlock(1, 65, 3, Blocks.STONE);
        source.setBlock(1, 66, 3, Blocks.GRASS_BLOCK);
        source.light.setUniform(2, 15);
        source.blockLight.set(1, 65, 3, 9);
        source.markModified();

        WorldGenerator generator = new WorldGenerator(42) {
            @Override public int sampleHeight(int x, int z) { return 66; }
            @Override public void generate(Chunk chunk) { }
            @Override public Biome biomeAt(int x, int z) { return Biomes.PLAINS; }
        };
        ChunkColumnSnapshot snapshot = ChunkSnapshotEncoder.encode(
                "skyengine:overworld", source, generator);

        assertEquals(1, snapshot.revision());
        assertEquals(1, snapshot.sections().size());
        assertEquals(2, snapshot.sections().getFirst().nonAir());
        assertEquals(LightPlane.Mode.UNIFORM_FULL, snapshot.sections().getFirst().skyLight().mode());
        assertEquals(LightPlane.Mode.PACKED_NIBBLES, snapshot.sections().getFirst().blockLight().mode());
        assertEquals(Biomes.PLAINS.id, snapshot.biomeId((3 << ChunkSection.SHIFT) | 1));
        assertEquals(Biomes.PLAINS.grassTint, snapshot.grassTintCorner(0));
        assertEquals(67, snapshot.height((3 << ChunkSection.SHIFT) | 1));

        Chunk decoded = LegacyChunkSnapshotDecoder.decode(snapshot);
        assertEquals(Blocks.STONE, decoded.getBlock(1, 65, 3));
        assertEquals(Blocks.GRASS_BLOCK, decoded.getBlock(1, 66, 3));
        assertEquals(15, decoded.light.get(1, 65, 3));
        assertEquals(9, decoded.blockLight.get(1, 65, 3));
        assertEquals(ChunkStatus.LIT, decoded.status);
    }

    @Test void generatedBiomeGridIsReusedWithoutResamplingTheGenerator() {
        Chunk source = new Chunk(0, 0);
        source.setBlock(0, 1, 0, Blocks.STONE);
        source.biomeIds = new int[ChunkSection.SIZE * ChunkSection.SIZE];
        java.util.Arrays.fill(source.biomeIds, Biomes.PLAINS.id);
        source.grassTintCorners = new int[(ChunkSection.SIZE + 1) * (ChunkSection.SIZE + 1)];
        source.foliageTintCorners = new int[(ChunkSection.SIZE + 1) * (ChunkSection.SIZE + 1)];
        java.util.concurrent.atomic.AtomicInteger biomeCalls = new java.util.concurrent.atomic.AtomicInteger();
        WorldGenerator generator = new WorldGenerator(7) {
            @Override public int sampleHeight(int x, int z) { return 1; }
            @Override public void generate(Chunk chunk) { }
            @Override public Biome biomeAt(int x, int z) {
                biomeCalls.incrementAndGet();
                return Biomes.PLAINS;
            }
        };

        ChunkColumnSnapshot snapshot = ChunkSnapshotEncoder.encode("skyengine:overworld", source, generator);

        assertEquals(0, biomeCalls.get());
        assertEquals(Biomes.PLAINS.id, snapshot.biomeId(0));
    }
}
