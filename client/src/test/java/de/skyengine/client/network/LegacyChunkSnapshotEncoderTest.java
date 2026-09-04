package de.skyengine.client.network;

import de.skyengine.game.world.block.Blocks;
import de.skyengine.game.world.chunk.Chunk;
import de.skyengine.game.world.generator.WorldGenerator;
import de.skyengine.game.world.generator.biome.Biome;
import de.skyengine.game.world.generator.biome.Biomes;
import de.skyengine.shared.world.ChunkSectionSnapshot;
import de.skyengine.shared.world.LightPlane;
import de.skyengine.test.BlocksTestBootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;

class LegacyChunkSnapshotEncoderTest {
    @BeforeAll static void bootstrap() {
        BlocksTestBootstrap.ensureBootstrapped();
    }

    @Test void copiesPaletteLightingTintsAndDerivedMetadata() {
        Chunk chunk = new Chunk(-2, 3);
        chunk.setBlock(0, 1, 0, Blocks.STONE);
        chunk.setBlock(1, 1, 0, Blocks.DIRT);
        chunk.light.setUniform(0, 15);
        chunk.blockLight.set(0, 1, 0, 7);
        chunk.grassTintCorners = filled(33 * 33, 0x123456);
        chunk.foliageTintCorners = filled(33 * 33, 0x654321);
        chunk.markModified();

        var snapshot = LegacyChunkSnapshotEncoder.encode("skyengine:overworld", generator(), chunk);

        assertEquals(-2, snapshot.chunkX());
        assertEquals(3, snapshot.chunkZ());
        assertEquals(1, snapshot.revision());
        assertEquals(2, snapshot.heightmap()[0]);
        assertEquals(Biomes.PLAINS.id, snapshot.biomeIds()[0]);
        assertEquals(0x123456, snapshot.grassTintCorners()[0]);
        assertEquals(0x654321, snapshot.foliageTintCorners()[0]);
        assertEquals(1, snapshot.sections().size());
        ChunkSectionSnapshot section = snapshot.sections().getFirst();
        assertEquals(2, section.nonAir());
        assertEquals(LightPlane.Mode.UNIFORM_FULL, section.skyLight().mode());
        assertEquals(LightPlane.Mode.PACKED_NIBBLES, section.blockLight().mode());
        assertEquals(7, section.blockLight().packedNibbles()[512] & 0xF);
        assertArrayEquals(new int[]{0, Blocks.STONE, Blocks.DIRT}, section.palette());

        chunk.setBlock(0, 1, 0, Blocks.AIR);
        chunk.grassTintCorners[0] = 0;
        assertEquals(2, snapshot.sections().getFirst().nonAir());
        assertEquals(0x123456, snapshot.grassTintCorners()[0]);
    }

    @Test void decoderRebuildsPaletteAndBothLightPlanesWithoutSharingSnapshotArrays() throws Exception {
        Chunk source = new Chunk(-2, 3);
        source.setBlock(0, 1, 0, Blocks.STONE);
        source.setBlock(31, 31, 31, Blocks.DIRT);
        source.light.setUniform(0, 15);
        source.blockLight.set(0, 1, 0, 7);
        source.grassTintCorners = filled(33 * 33, 0x123456);
        source.foliageTintCorners = filled(33 * 33, 0x654321);

        var snapshot = LegacyChunkSnapshotEncoder.encode("skyengine:overworld", generator(), source);
        Chunk decoded = LegacyChunkSnapshotDecoder.decode(snapshot);

        assertEquals(Blocks.STONE, decoded.getBlock(0, 1, 0));
        assertEquals(Blocks.DIRT, decoded.getBlock(31, 31, 31));
        assertEquals(15, decoded.light.get(17, 7, 4));
        assertEquals(7, decoded.blockLight.get(0, 1, 0));
        assertEquals(0, decoded.blockLight.get(1, 1, 0));
        assertArrayEquals(snapshot.heightmap(), decoded.heightmap);
        assertArrayEquals(snapshot.grassTintCorners(), decoded.grassTintCorners);
        assertArrayEquals(snapshot.foliageTintCorners(), decoded.foliageTintCorners);
        assertNotSame(snapshot.grassTintCorners(), decoded.grassTintCorners);
    }

    @Test void decoderRestoresImplicitSkylightForOmittedSectionsAboveTerrain() throws Exception {
        Chunk source = new Chunk(0, 0);
        source.setBlock(0, 63, 0, Blocks.GRASS_BLOCK);
        source.heightmap = filled(32 * 32, 64);
        source.light.setUniform(1, 15);

        var snapshot = LegacyChunkSnapshotEncoder.encode("skyengine:overworld", generator(), source);
        Chunk decoded = LegacyChunkSnapshotDecoder.decode(snapshot);

        assertEquals(15, decoded.light.get(0, 64, 0));
        assertEquals(15, decoded.light.get(31, 511, 31));
        assertEquals(0, decoded.blockLight.get(0, 64, 0));
    }

    private static int[] filled(int size, int value) {
        int[] values = new int[size];
        java.util.Arrays.fill(values, value);
        return values;
    }

    private static WorldGenerator generator() {
        return new WorldGenerator(1) {
            @Override public int sampleHeight(int x, int z) { return 1; }
            @Override public void generate(Chunk chunk) { }
            @Override public Biome biomeAt(int x, int z) { return Biomes.PLAINS; }
        };
    }
}
