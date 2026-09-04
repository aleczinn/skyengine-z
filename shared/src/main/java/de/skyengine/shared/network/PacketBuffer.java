package de.skyengine.shared.network;

import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.util.Arrays;
import java.util.UUID;

/** Bounds-checked binary buffer. It deliberately has no dependency on Netty or Java serialization. */
public final class PacketBuffer {
    private byte[] data;
    private int readerIndex;
    private int writerIndex;

    public PacketBuffer() { this(128); }

    public PacketBuffer(int initialCapacity) {
        if (initialCapacity < 0 || initialCapacity > ProtocolLimits.MAX_DECOMPRESSED_BYTES) {
            throw new IllegalArgumentException("Invalid capacity " + initialCapacity);
        }
        this.data = new byte[Math.max(1, initialCapacity)];
    }

    private PacketBuffer(byte[] source) {
        if (source.length > ProtocolLimits.MAX_DECOMPRESSED_BYTES) {
            throw new IllegalArgumentException("Payload too large: " + source.length);
        }
        this.data = source;
        this.writerIndex = source.length;
    }

    public static PacketBuffer wrap(byte[] source) {
        return new PacketBuffer(Arrays.copyOf(source, source.length));
    }

    public int readableBytes() { return this.writerIndex - this.readerIndex; }
    public boolean isReadable() { return this.readerIndex < this.writerIndex; }

    public byte[] toByteArray() { return Arrays.copyOf(this.data, this.writerIndex); }

    public void requireFullyRead() throws ProtocolException {
        if (isReadable()) throw new ProtocolException("Trailing packet bytes: " + readableBytes());
    }

    public void writeByte(int value) {
        ensureWritable(1);
        this.data[this.writerIndex++] = (byte) value;
    }

    public int readUnsignedByte() throws ProtocolException { return readByte() & 0xFF; }

    public byte readByte() throws ProtocolException {
        requireReadable(1);
        return this.data[this.readerIndex++];
    }

    public void writeBoolean(boolean value) { writeByte(value ? 1 : 0); }

    public boolean readBoolean() throws ProtocolException {
        int value = readUnsignedByte();
        if (value > 1) throw new ProtocolException("Invalid boolean " + value);
        return value != 0;
    }

    public void writeShort(int value) {
        ensureWritable(2);
        this.data[this.writerIndex++] = (byte) (value >>> 8);
        this.data[this.writerIndex++] = (byte) value;
    }

    public int readUnsignedShort() throws ProtocolException {
        requireReadable(2);
        return ((this.data[this.readerIndex++] & 0xFF) << 8) | (this.data[this.readerIndex++] & 0xFF);
    }

    public void writeInt(int value) {
        ensureWritable(4);
        this.data[this.writerIndex++] = (byte) (value >>> 24);
        this.data[this.writerIndex++] = (byte) (value >>> 16);
        this.data[this.writerIndex++] = (byte) (value >>> 8);
        this.data[this.writerIndex++] = (byte) value;
    }

    public int readInt() throws ProtocolException {
        requireReadable(4);
        return ((this.data[this.readerIndex++] & 0xFF) << 24)
                | ((this.data[this.readerIndex++] & 0xFF) << 16)
                | ((this.data[this.readerIndex++] & 0xFF) << 8)
                | (this.data[this.readerIndex++] & 0xFF);
    }

    public void writeLong(long value) {
        ensureWritable(8);
        for (int shift = 56; shift >= 0; shift -= 8) this.data[this.writerIndex++] = (byte) (value >>> shift);
    }

    public long readLong() throws ProtocolException {
        requireReadable(8);
        long value = 0;
        for (int i = 0; i < 8; i++) value = (value << 8) | (this.data[this.readerIndex++] & 0xFFL);
        return value;
    }

    public void writeFloat(float value) { writeInt(Float.floatToRawIntBits(value)); }
    public float readFloat() throws ProtocolException { return Float.intBitsToFloat(readInt()); }
    public void writeDouble(double value) { writeLong(Double.doubleToRawLongBits(value)); }
    public double readDouble() throws ProtocolException { return Double.longBitsToDouble(readLong()); }

    public void writeVarInt(int value) {
        while ((value & ~0x7F) != 0) {
            writeByte((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        writeByte(value);
    }

    public int readVarInt() throws ProtocolException {
        int value = 0;
        for (int i = 0; i < 5; i++) {
            int current = readUnsignedByte();
            value |= (current & 0x7F) << (i * 7);
            if ((current & 0x80) == 0) {
                if (i == 4 && (current & 0xF0) != 0) throw new ProtocolException("VarInt overflow");
                if (i > 0 && (current & 0x7F) == 0) throw new ProtocolException("Non-canonical VarInt");
                return value;
            }
        }
        throw new ProtocolException("VarInt exceeds 5 bytes");
    }

    public void writeVarLong(long value) {
        while ((value & ~0x7FL) != 0) {
            writeByte(((int) value & 0x7F) | 0x80);
            value >>>= 7;
        }
        writeByte((int) value);
    }

    public long readVarLong() throws ProtocolException {
        long value = 0;
        for (int i = 0; i < 10; i++) {
            int current = readUnsignedByte();
            value |= (long) (current & 0x7F) << (i * 7);
            if ((current & 0x80) == 0) {
                if (i == 9 && (current & 0xFE) != 0) throw new ProtocolException("VarLong overflow");
                if (i > 0 && (current & 0x7F) == 0) throw new ProtocolException("Non-canonical VarLong");
                return value;
            }
        }
        throw new ProtocolException("VarLong exceeds 10 bytes");
    }

    public void writeUuid(UUID uuid) {
        writeLong(uuid.getMostSignificantBits());
        writeLong(uuid.getLeastSignificantBits());
    }

    public UUID readUuid() throws ProtocolException { return new UUID(readLong(), readLong()); }

    public void writeString(String value, int maximumBytes) throws ProtocolException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > maximumBytes) throw new ProtocolException("String exceeds " + maximumBytes + " bytes");
        writeVarInt(bytes.length);
        writeRawBytes(bytes);
    }

    public String readString(int maximumBytes) throws ProtocolException {
        int length = readVarInt();
        if (length < 0 || length > maximumBytes) throw new ProtocolException("Invalid string length " + length);
        byte[] bytes = readRawBytes(length);
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            throw new ProtocolException("Malformed UTF-8 string", e);
        }
    }

    public void writeByteArray(byte[] bytes, int maximumBytes) throws ProtocolException {
        if (bytes.length > maximumBytes) throw new ProtocolException("Byte array exceeds " + maximumBytes + " bytes");
        writeVarInt(bytes.length);
        writeRawBytes(bytes);
    }

    public byte[] readByteArray(int maximumBytes) throws ProtocolException {
        int length = readVarInt();
        if (length < 0 || length > maximumBytes) throw new ProtocolException("Invalid byte array length " + length);
        return readRawBytes(length);
    }

    public void writeRawBytes(byte[] bytes) {
        ensureWritable(bytes.length);
        System.arraycopy(bytes, 0, this.data, this.writerIndex, bytes.length);
        this.writerIndex += bytes.length;
    }

    public void writeRawBytes(ByteBuffer source) {
        ByteBuffer view = source.slice();
        int length = view.remaining();
        ensureWritable(length);
        view.get(this.data, this.writerIndex, length);
        this.writerIndex += length;
    }

    public byte[] readRawBytes(int length) throws ProtocolException {
        requireReadable(length);
        byte[] result = Arrays.copyOfRange(this.data, this.readerIndex, this.readerIndex + length);
        this.readerIndex += length;
        return result;
    }

    private void requireReadable(int length) throws ProtocolException {
        if (length < 0 || length > readableBytes()) throw new ProtocolException("Truncated packet");
    }

    private void ensureWritable(int length) {
        if (length < 0 || this.writerIndex > ProtocolLimits.MAX_DECOMPRESSED_BYTES - length) {
            throw new IllegalStateException("Packet exceeds maximum size");
        }
        int needed = this.writerIndex + length;
        if (needed <= this.data.length) return;
        int capacity = this.data.length;
        while (capacity < needed) capacity = Math.min(ProtocolLimits.MAX_DECOMPRESSED_BYTES, capacity << 1);
        if (capacity < needed) throw new IllegalStateException("Packet exceeds maximum size");
        this.data = Arrays.copyOf(this.data, capacity);
    }
}
