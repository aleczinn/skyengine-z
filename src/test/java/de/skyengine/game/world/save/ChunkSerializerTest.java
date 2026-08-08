package de.skyengine.game.world.save;

import de.skyengine.game.world.block.Blocks;
import de.skyengine.game.entity.ItemFrameEntity;
import de.skyengine.game.entity.MinecartEntity;
import de.skyengine.game.world.block.Direction;
import de.skyengine.game.world.block.Identifier;
import de.skyengine.game.world.block.entity.ComparatorBlockEntity;
import de.skyengine.game.world.block.state.Properties;
import de.skyengine.game.world.block.state.BlockStateCodec;
import de.skyengine.game.world.chunk.Chunk;
import de.skyengine.game.world.chunk.ChunkSection;
import de.skyengine.game.world.item.ItemStack;
import de.skyengine.game.world.item.Items;
import de.skyengine.game.world.tick.SavedTick;
import de.skyengine.game.world.tick.ScheduledTickTypes;
import de.skyengine.test.BlocksTestBootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    void itemFramePositionDirectionContentAndRotationRoundTrip() throws Exception {
        Chunk source = new Chunk(-2, 5);
        int x = (source.chunkX << 5) + 7;
        int z = (source.chunkZ << 5) + 11;
        ItemFrameEntity frame = new ItemFrameEntity(x, 64, z, Direction.WEST);
        frame.loadContent(new ItemStack(Items.get(Identifier.of("skyengine:diamond")), 1), 6);
        source.addEntity(frame);

        byte[] payload = ChunkSerializer.serialize(source, "test", 1, false,
                List.of(), List.of(), ChunkSerializer.snapshotEntities(source));
        Chunk restored = new Chunk(source.chunkX, source.chunkZ);
        ChunkSerializer.deserialize(restored, payload, null);

        assertEquals(1, restored.entities().size());
        ItemFrameEntity loaded = (ItemFrameEntity) restored.entities().getFirst();
        assertEquals(x, loaded.getAnchorX());
        assertEquals(64, loaded.getAnchorY());
        assertEquals(z, loaded.getAnchorZ());
        assertEquals(Direction.WEST, loaded.getDirection());
        assertEquals(6, loaded.getRotation());
        assertEquals(Identifier.of("skyengine:diamond"), loaded.getItem().getItem().getId());
        assertEquals(7, loaded.getAnalogOutput());
    }

    @Test
    void minecartPositionMotionAndRotationRoundTrip() throws Exception {
        Chunk source = new Chunk(-2, 5);
        MinecartEntity minecart = new MinecartEntity();
        minecart.setPosition(-55.25, 70.0625, 171.75);
        minecart.motionX = 0.21;
        minecart.motionY = -0.04;
        minecart.motionZ = -0.12;
        minecart.yaw = 123.5F;
        minecart.setDamage(23.0F);
        minecart.setHurtTime(7);
        minecart.setHurtDirection(-1);
        source.addEntity(minecart);

        byte[] payload = ChunkSerializer.serialize(source, "test", 1, false,
                List.of(), List.of(), ChunkSerializer.snapshotEntities(source));
        Chunk restored = new Chunk(source.chunkX, source.chunkZ);
        ChunkSerializer.deserialize(restored, payload, null);

        assertEquals(1, restored.entities().size());
        MinecartEntity loaded = (MinecartEntity) restored.entities().getFirst();
        assertEquals(minecart.x, loaded.x);
        assertEquals(minecart.y, loaded.y);
        assertEquals(minecart.z, loaded.z);
        assertEquals(minecart.motionX, loaded.motionX);
        assertEquals(minecart.motionY, loaded.motionY);
        assertEquals(minecart.motionZ, loaded.motionZ);
        assertEquals(minecart.yaw, loaded.yaw);
        assertEquals(minecart.getDamage(), loaded.getDamage());
        assertEquals(minecart.getHurtTime(), loaded.getHurtTime());
        assertEquals(minecart.getHurtDirection(), loaded.getHurtDirection());
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

    @Test
    void legacyComparatorPowerMigratesIntoSynthesizedBlockEntity() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeByte(2);
            out.writeUTF("test");
            out.writeInt(1);
            out.writeInt(2);
            out.writeUTF("skyengine:air");
            out.writeUTF("skyengine:comparator[facing=east,mode=compare,power=7]");

            out.writeByte(2); // SECTION_BITS
            out.writeInt(2);
            out.writeInt(0); // air
            out.writeInt(1); // legacy comparator
            out.writeByte(1);
            out.writeInt(1);
            out.writeInt(512);
            out.writeLong(1L); // lokale Position 0 -> Palettenindex 1
            for (int i = 1; i < 512; i++) out.writeLong(0L);
            for (int section = 1; section < Chunk.SECTIONS; section++) out.writeByte(0);
            out.writeByte(0); // keine Tints
            out.writeInt(0);  // alte Saves hatten keine Comparator-BE
            out.writeInt(0);  // keine Scheduled-Ticks
        }

        Chunk restored = new Chunk(0, 0);
        ChunkSerializer.deserialize(restored, bytes.toByteArray(), null);

        assertTrue(Blocks.getState(restored.getBlock(0, 0, 0)).get(Properties.POWERED));
        ComparatorBlockEntity comparator =
                (ComparatorBlockEntity) restored.getBlockEntity(0, 0, 0);
        assertNotNull(comparator);
        assertEquals(7, comparator.getOutputSignal());
    }

    @Test
    void corruptCollectionLengthsFailBeforeAllocationOrIteration() throws Exception {
        assertThrows(IOException.class, () -> ChunkSerializer.deserialize(
                new Chunk(0, 0), payloadWithLongCount(Integer.MAX_VALUE), null));
        assertThrows(IOException.class, () -> ChunkSerializer.deserialize(
                new Chunk(0, 0), payloadWithBlockEntityCount(-1), null));
        assertThrows(IOException.class, () -> ChunkSerializer.decompress(
                new byte[]{1}, 64 * 1024 * 1024 + 1));
    }

    @Test
    void duplicatedLegacySectionSlotsMayExceedDeduplicatedChunkPalette() throws Exception {
        final int globalCount = 168;
        final int localCount = 173;
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            writeHeader(out, globalCount);
            for (int stateId = 0; stateId < globalCount; stateId++) {
                out.writeUTF(BlockStateCodec.encode(Blocks.getState(stateId)));
            }

            out.writeByte(2); // SECTION_BITS
            out.writeInt(localCount);
            for (int i = 0; i < localCount; i++) out.writeInt((i + 1) % globalCount);
            out.writeByte(8);
            out.writeInt(ChunkSection.VOLUME);
            int longCount = ChunkSection.VOLUME * 8 / Long.SIZE;
            out.writeInt(longCount);
            for (int i = 0; i < longCount; i++) out.writeLong(0L);

            for (int section = 1; section < Chunk.SECTIONS; section++) out.writeByte(0);
            out.writeByte(0); // keine Tints
            out.writeInt(0);  // keine BlockEntities
            out.writeInt(0);  // keine Scheduled-Ticks
            out.writeInt(0);  // keine Entities
        }

        Chunk restored = new Chunk(-1, 0);
        ChunkSerializer.deserialize(restored, bytes.toByteArray(), null);

        assertEquals(1, restored.getBlock(0, 0, 0));
        assertEquals(localCount, restored.getSection(0).container().paletteEntries().length);
    }

    @Test
    void excessiveDataTagNestingFailsAsIOException() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            for (int i = 0; i < 65; i++) {
                out.writeByte(6); // TYPE_TAG
                out.writeUTF("nested");
            }
            for (int i = 0; i <= 65; i++) out.writeByte(0); // TYPE_END
        }

        assertThrows(IOException.class, () -> DataTagIO.read(
                new DataInputStream(new java.io.ByteArrayInputStream(bytes.toByteArray()))));
    }

    private static byte[] payloadWithLongCount(int longCount) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            writeHeader(out, 1);
            out.writeUTF("skyengine:stone");
            out.writeByte(2); // SECTION_BITS
            out.writeInt(1);
            out.writeInt(0);
            out.writeByte(1);
            out.writeInt(1);
            out.writeInt(longCount);
        }
        return bytes.toByteArray();
    }

    private static byte[] payloadWithBlockEntityCount(int beCount) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            writeHeader(out, 0);
            for (int section = 0; section < Chunk.SECTIONS; section++) out.writeByte(0);
            out.writeByte(0);
            out.writeInt(beCount);
        }
        return bytes.toByteArray();
    }

    private static void writeHeader(DataOutputStream out, int paletteCount) throws IOException {
        out.writeByte(ChunkSerializer.PAYLOAD_VERSION);
        out.writeUTF("test");
        out.writeInt(1);
        out.writeInt(paletteCount);
    }
}
