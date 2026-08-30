package de.skyengine.game.world.generator;

import de.skyengine.game.world.chunk.Chunk;
import de.skyengine.game.world.lod.LodVolumeRequest;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class WorldGeneratorLodVolumeTest {

    @Test
    void verticalHaloOutsideWorldDoesNotSampleTerrain() {
        AtomicInteger samples = new AtomicInteger();
        AtomicInteger writes = new AtomicInteger();
        WorldGenerator generator = new WorldGenerator(1) {
            @Override public int sampleHeight(int x, int z) { return 64; }
            @Override public LodSurfaces sampleLodSurfaces(int x, int z) {
                samples.incrementAndGet();
                long surface = SurfaceSample.pack(1, 64);
                return new LodSurfaces(surface, surface);
            }
            @Override public void generate(Chunk chunk) {}
        };

        generator.fillLodVolume(new LodVolumeRequest(0, -1, 0, 4),
                (x, y, z, voxel) -> writes.incrementAndGet());
        generator.fillLodVolume(new LodVolumeRequest(0, 1, 0, 4),
                (x, y, z, voxel) -> writes.incrementAndGet());

        assertEquals(0, samples.get());
        assertEquals(0, writes.get());
    }

    @Test
    void oneHaloColumnSamplesOnlyOneTerrainColumn() {
        AtomicInteger samples = new AtomicInteger();
        AtomicInteger writes = new AtomicInteger();
        WorldGenerator generator = new WorldGenerator(1) {
            @Override public int sampleHeight(int x, int z) { return 64; }
            @Override public LodSurfaces sampleLodSurfaces(int x, int z) {
                samples.incrementAndGet();
                long surface = SurfaceSample.pack(1, 64);
                return new LodSurfaces(surface, surface);
            }
            @Override public void generate(Chunk chunk) {}
        };

        generator.fillLodVolumeColumn(new LodVolumeRequest(2, 0, -3, 4), 31, 7,
                (x, y, z, voxel) -> writes.incrementAndGet());

        assertEquals(1, samples.get());
        assertEquals(5, writes.get());
    }
}
