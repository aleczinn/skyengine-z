package de.skyengine.shared.network;

import de.skyengine.shared.network.packets.CorePackets;
import de.skyengine.shared.world.ChunkColumnSnapshot;
import de.skyengine.shared.world.ChunkSectionSnapshot;
import de.skyengine.shared.world.LightPlane;
import de.skyengine.shared.world.BlockEntitySnapshot;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ChunkProtocolTest {
    @Test
    void nonEmptyPaletteSectionRoundTripsThroughBoundedChunkPacket() throws Exception {
        int[] biomes = new int[ChunkColumnSnapshot.COLUMN_CELLS];
        int[] grass = new int[ChunkColumnSnapshot.TINT_CORNERS];
        int[] foliage = new int[ChunkColumnSnapshot.TINT_CORNERS];
        int[] heightmap = new int[ChunkColumnSnapshot.COLUMN_CELLS];
        java.util.Arrays.fill(grass, 0x55aa33);
        java.util.Arrays.fill(foliage, 0x338833);
        ChunkSectionSnapshot section = new ChunkSectionSnapshot(3, ChunkSectionSnapshot.VOLUME,
                new int[] {42}, 0, new long[0],
                new LightPlane(LightPlane.Mode.UNIFORM_FULL, null),
                new LightPlane(LightPlane.Mode.UNIFORM_ZERO, null));
        BlockEntitySnapshot blockEntity = new BlockEntitySnapshot(2, 65, 31,
                "skyengine:chest", new byte[] {1, 2, 3, 0});
        ChunkColumnSnapshot chunk = new ChunkColumnSnapshot("skyengine:overworld", -4, 7, 19,
                List.of(section), biomes, grass, foliage, heightmap, List.of(blockEntity));
        PacketRegistry registry = CoreProtocol.createRegistry();
        byte[] body = registry.encode(PacketDirection.SERVER_TO_CLIENT, ConnectionState.PLAY,
                new PacketEnvelope(new CorePackets.ChunkColumnData(5, chunk)));
        CorePackets.ChunkColumnData decoded = assertInstanceOf(CorePackets.ChunkColumnData.class,
                registry.decode(PacketDirection.SERVER_TO_CLIENT, ConnectionState.PLAY, body).packet());

        assertEquals(5, decoded.batchId());
        assertEquals(-4, decoded.chunk().chunkX());
        assertEquals(7, decoded.chunk().chunkZ());
        assertEquals(19, decoded.chunk().revision());
        assertEquals(1, decoded.chunk().sections().size());
        assertArrayEquals(new int[] {42}, decoded.chunk().sections().getFirst().palette());
        assertArrayEquals(grass, decoded.chunk().grassTintCorners());
        assertEquals(1, decoded.chunk().blockEntities().size());
        assertEquals("skyengine:chest", decoded.chunk().blockEntities().getFirst().typeId());
        assertArrayEquals(blockEntity.data(), decoded.chunk().blockEntities().getFirst().data());

        CorePackets.BlockEntityUpdate update = new CorePackets.BlockEntityUpdate(
                "skyengine:overworld", -4, 7, blockEntity);
        body = registry.encode(PacketDirection.SERVER_TO_CLIENT, ConnectionState.PLAY,
                new PacketEnvelope(update));
        CorePackets.BlockEntityUpdate decodedUpdate = assertInstanceOf(
                CorePackets.BlockEntityUpdate.class, registry.decode(PacketDirection.SERVER_TO_CLIENT,
                        ConnectionState.PLAY, body).packet());
        assertEquals(65, decodedUpdate.blockEntity().y());
        assertArrayEquals(blockEntity.data(), decodedUpdate.blockEntity().data());
    }

    @Test
    void malformedSectionWordCountIsRejected() throws Exception {
        PacketBuffer body = new PacketBuffer();
        body.writeByte(LogicalChannel.CHUNK_DATA.ordinal());
        body.writeByte(0);
        body.writeVarInt(21);
        body.writeVarLong(1);
        body.writeString("skyengine:overworld", ProtocolLimits.MAX_IDENTIFIER_BYTES);
        body.writeInt(0); body.writeInt(0); body.writeVarLong(0);
        body.writeVarInt(1);
        body.writeByte(0); body.writeVarInt(1); body.writeVarInt(2);
        body.writeVarInt(0); body.writeVarInt(1);
        body.writeByte(1);
        body.writeVarInt(0); // 1-bit 32^3 section requires 512 words.
        PacketRegistry registry = CoreProtocol.createRegistry();
        assertThrows(ProtocolException.class, () -> registry.decode(PacketDirection.SERVER_TO_CLIENT,
                ConnectionState.PLAY, body.toByteArray()));
    }
}
