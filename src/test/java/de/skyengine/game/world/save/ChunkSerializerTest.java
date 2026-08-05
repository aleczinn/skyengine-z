package de.skyengine.game.world.save;

import de.skyengine.game.world.block.Blocks;
import de.skyengine.game.world.chunk.Chunk;
import de.skyengine.game.world.tick.SavedTick;
import de.skyengine.game.world.tick.ScheduledTickTypes;
import de.skyengine.test.BlocksTestBootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

final class ChunkSerializerTest {

    @BeforeAll
    static void bootstrapBlocks() {
        BlocksTestBootstrap.ensureBootstrapped();
    }

    @Test
    void blockStatesAndScheduledTicksRoundTripHeadlessly() throws Exception {
        Chunk source = new Chunk(-2, 5);
        source.setBlock(3, 64, 7, Blocks.STONE);
        int worldX = source.chunkX * 32 + 3;
        int worldZ = source.chunkZ * 32 + 7;
        List<SavedTick> ticks = List.of(
                new SavedTick(ScheduledTickTypes.BLOCK, worldX, 64, worldZ, 4));

        byte[] payload = ChunkSerializer.serialize(
                source, "test", 1, false, ticks, List.of());
        Chunk restored = new Chunk(source.chunkX, source.chunkZ);
        ChunkSerializer.deserialize(restored, payload, null);

        assertEquals(Blocks.STONE, restored.getBlock(3, 64, 7));
        assertNotNull(restored.pendingScheduledTicks);
        assertEquals(ticks, restored.pendingScheduledTicks);
    }
}
