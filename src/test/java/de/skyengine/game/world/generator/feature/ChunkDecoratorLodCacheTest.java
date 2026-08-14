package de.skyengine.game.world.generator.feature;

import de.skyengine.game.world.chunk.Chunk;
import de.skyengine.game.world.generator.WorldGenerator;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class ChunkDecoratorLodCacheTest {

    @Test
    void overlappingTargetsReuseSourceTilesAndKeepCommandOrder() {
        AtomicInteger placements = new AtomicInteger();
        Feature feature = context -> {
            placements.incrementAndGet();
            int sx = context.sourceMinX() >> 5;
            int sz = context.sourceMinZ() >> 5;
            context.set(0, 80, 0, 100 + (sx + 1) * 3 + sz + 1);
        };
        ChunkDecorator decorator = new ChunkDecorator(new FlatGenerator(), List.of(feature));

        LodFeatureBuffer first = decorator.decorateForLod(0, 0);
        decorator.decorateForLod(1, 0);

        int[] state = {-1};
        first.forEach((x, y, z, block) -> {
            if (x == 0 && y == 80 && z == 0) state[0] = block;
        });
        assertEquals(108, state[0]);
        assertEquals(12, placements.get(), "9 erste Tiles plus 3 neue Tiles des Nachbarchunks");
    }

    private static final class FlatGenerator extends WorldGenerator {
        private FlatGenerator() { super(1); }
        @Override public int sampleHeight(int x, int z) { return 64; }
        @Override public void generate(Chunk chunk) {}
    }
}
