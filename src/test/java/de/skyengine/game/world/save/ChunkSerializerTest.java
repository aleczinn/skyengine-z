package de.skyengine.game.world.save;

import de.skyengine.game.world.block.Blocks;
import de.skyengine.game.world.chunk.Chunk;
import de.skyengine.game.world.tick.SavedTick;
import de.skyengine.game.world.tick.ScheduledTickTypes;
import de.skyengine.test.BlocksTestBootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

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
                new SavedTick(ScheduledTickTypes.BLOCK, "skyengine:stone",
                        worldX, 64, worldZ, 4, -1, 42));

        byte[] payload = ChunkSerializer.serialize(
                source, "test", 1, false, ticks, List.of());
        Chunk restored = new Chunk(source.chunkX, source.chunkZ);
        ChunkSerializer.deserialize(restored, payload, null);

        assertEquals(Blocks.STONE, restored.getBlock(3, 64, 7));
        assertNotNull(restored.pendingScheduledTicks);
        assertEquals(ticks, restored.pendingScheduledTicks);
    }

    @Test
    void legacyV2TicksRemainReadableAndAreMarkedAsUnbound() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeByte(2);
            out.writeUTF("test");
            out.writeInt(1);
            out.writeInt(0);
            for (int section = 0; section < 16; section++) out.writeByte(0);
            out.writeByte(0);
            out.writeInt(0);
            out.writeInt(1);
            out.writeUTF(ScheduledTickTypes.BLOCK);
            out.writeInt(3);
            out.writeInt(64);
            out.writeInt(7);
            out.writeInt(5);
        }

        Chunk restored = new Chunk(0, 0);
        ChunkSerializer.deserialize(restored, bytes.toByteArray(), null);

        assertNotNull(restored.pendingScheduledTicks);
        assertEquals(1, restored.pendingScheduledTicks.size());
        SavedTick tick = restored.pendingScheduledTicks.getFirst();
        assertEquals(ScheduledTickTypes.BLOCK, tick.type());
        assertNull(tick.expectedBlock());
        assertEquals(5, tick.remainingTicks());
        assertEquals(0, tick.priority());
        assertEquals(0, tick.subOrder());
    }
}
