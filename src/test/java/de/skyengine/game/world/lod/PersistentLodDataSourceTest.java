package de.skyengine.game.world.lod;

import de.skyengine.game.world.chunk.Chunk;
import de.skyengine.game.world.chunk.ChunkManager;
import de.skyengine.game.world.generator.SurfaceSample;
import de.skyengine.game.world.generator.WorldGenerator;
import de.skyengine.game.world.generator.feature.ChunkDecorator;
import de.skyengine.game.world.save.WorldStorage;
import de.skyengine.test.BlocksTestBootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

final class PersistentLodDataSourceTest {

    @TempDir File temporary;

    @BeforeAll
    static void bootstrapBlocks() {
        BlocksTestBootstrap.ensureBootstrapped();
    }

    @Test
    void unknownLevelOneColumnIsGeneratedAsOneSharedVerticalStack() {
        AtomicInteger samples = new AtomicInteger();
        WorldGenerator generator = new WorldGenerator(17) {
            @Override public int sampleHeight(int x, int z) { return 70; }
            @Override public LodSurfaces sampleLodSurfaces(int x, int z) {
                samples.incrementAndGet();
                long surface = SurfaceSample.pack(1, 70);
                return new LodSurfaces(surface, surface);
            }
            @Override public void generate(Chunk chunk) {}
        };
        ChunkDecorator decorator = new ChunkDecorator(generator, List.of());
        ChunkManager chunks = new ChunkManager(generator, decorator);
        WorldStorage storage = new WorldStorage(new File(this.temporary, "region"), null,
                generator, "test", 1, false);

        try (PersistentLodDataSource source = new PersistentLodDataSource(chunks, storage,
                generator, decorator, false, new File(this.temporary, "lod"), 1)) {
            assertNotNull(source.volume(-3, 0, 5, 1));
            for (int nodeY = 0; nodeY < 8; nodeY++) {
                assertNotNull(source.availableVolume(-3, nodeY, 5, 1));
            }
            assertEquals(1_024, samples.get());
            assertEquals(8, source.volumeNodeCount());
        } finally {
            chunks.dispose();
            storage.close();
        }
    }

    @Test
    void warmV5ColumnLoadsAtomicallyWithoutCallingGeneratorAgain() {
        AtomicInteger samples = new AtomicInteger();
        WorldGenerator generator = new WorldGenerator(23) {
            @Override public int sampleHeight(int x, int z) { return 74; }
            @Override public LodSurfaces sampleLodSurfaces(int x, int z) {
                samples.incrementAndGet();
                long surface = SurfaceSample.pack(1, 74);
                return new LodSurfaces(surface, surface);
            }
            @Override public void generate(Chunk chunk) {}
        };
        File region = new File(this.temporary, "warm-region");
        File lod = new File(this.temporary, "warm-lod");

        ChunkDecorator firstDecorator = new ChunkDecorator(generator, List.of());
        ChunkManager firstChunks = new ChunkManager(generator, firstDecorator);
        WorldStorage firstStorage = new WorldStorage(region, null, generator, "test", 1, false);
        try (PersistentLodDataSource source = new PersistentLodDataSource(firstChunks, firstStorage,
                generator, firstDecorator, false, lod, 1)) {
            assertNotNull(source.volume(4, 0, -6, 1));
            assertEquals(1, source.generatedColumns());
        } finally {
            firstChunks.dispose();
            firstStorage.close();
        }

        samples.set(0);
        ChunkDecorator secondDecorator = new ChunkDecorator(generator, List.of());
        ChunkManager secondChunks = new ChunkManager(generator, secondDecorator);
        WorldStorage secondStorage = new WorldStorage(region, null, generator, "test", 1, false);
        try (PersistentLodDataSource source = new PersistentLodDataSource(secondChunks, secondStorage,
                generator, secondDecorator, false, lod, 1)) {
            assertNotNull(source.volume(4, 0, -6, 1));
            for (int y = 0; y < 8; y++) assertNotNull(source.availableVolume(4, y, -6, 1));
            assertEquals(0, samples.get());
            assertEquals(1, source.cacheHitCount());
            assertEquals(0, source.generatedColumns());
        } finally {
            secondChunks.dispose();
            secondStorage.close();
        }
    }
}
