package de.skyengine.shared.network;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PacketBufferTest {
    @Test
    void primitiveRoundTripIsLossless() throws Exception {
        UUID uuid = UUID.fromString("01234567-89ab-cdef-0123-456789abcdef");
        PacketBuffer output = new PacketBuffer(1);
        output.writeBoolean(true);
        output.writeByte(0xfe);
        output.writeShort(0xcafe);
        output.writeInt(0x89abcdef);
        output.writeLong(0x0123456789abcdefL);
        output.writeFloat(-12.5f);
        output.writeDouble(Math.PI);
        output.writeVarInt(-1);
        output.writeVarLong(-1L);
        output.writeUuid(uuid);
        output.writeString("Grüße aus SkyEngine", 128);
        output.writeByteArray(new byte[] {1, 2, 3}, 16);

        PacketBuffer input = PacketBuffer.wrap(output.toByteArray());
        assertTrue(input.readBoolean());
        assertEquals(0xfe, input.readUnsignedByte());
        assertEquals(0xcafe, input.readUnsignedShort());
        assertEquals(0x89abcdef, input.readInt());
        assertEquals(0x0123456789abcdefL, input.readLong());
        assertEquals(-12.5f, input.readFloat());
        assertEquals(Math.PI, input.readDouble());
        assertEquals(-1, input.readVarInt());
        assertEquals(-1L, input.readVarLong());
        assertEquals(uuid, input.readUuid());
        assertEquals("Grüße aus SkyEngine", input.readString(128));
        assertArrayEquals(new byte[] {1, 2, 3}, input.readByteArray(16));
        assertFalse(input.isReadable());
    }

    @Test
    void malformedInputIsRejectedBeforeAllocation() {
        assertThrows(ProtocolException.class,
                () -> PacketBuffer.wrap(new byte[] {(byte) 0x80, (byte) 0x80, (byte) 0x80,
                        (byte) 0x80, (byte) 0x80, 0}).readVarInt());
        assertThrows(ProtocolException.class,
                () -> PacketBuffer.wrap(new byte[] {(byte) 0xff, (byte) 0xff, (byte) 0xff,
                        (byte) 0xff, 0x10}).readVarInt());
        assertThrows(ProtocolException.class,
                () -> PacketBuffer.wrap(new byte[] {(byte) 0x80, 0}).readVarInt());
        assertThrows(ProtocolException.class,
                () -> PacketBuffer.wrap(new byte[] {(byte) 0x81, (byte) 0x80, 0}).readVarLong());
        assertThrows(ProtocolException.class,
                () -> PacketBuffer.wrap(new byte[] {5, 'a'}).readString(8));
        assertThrows(ProtocolException.class,
                () -> PacketBuffer.wrap(new byte[] {9}).readString(8));
        assertThrows(ProtocolException.class,
                () -> PacketBuffer.wrap(new byte[] {2, (byte) 0xc3, 0x28}).readString(8));
        assertThrows(ProtocolException.class,
                () -> PacketBuffer.wrap(new byte[] {2}).readBoolean());
    }
}
