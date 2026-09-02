package de.skyengine.shared.network;

/** Stateless length-prefix framing used before transport compression is enabled. */
public final class ProtocolFraming {
    public static byte[] frame(byte[] body) throws ProtocolException {
        if (body.length > ProtocolLimits.MAX_FRAME_BYTES) throw new ProtocolException("Frame too large: " + body.length);
        PacketBuffer result = new PacketBuffer(body.length + 5);
        result.writeVarInt(body.length);
        result.writeRawBytes(body);
        return result.toByteArray();
    }

    public static byte[] unframe(byte[] framed) throws ProtocolException {
        PacketBuffer input = PacketBuffer.wrap(framed);
        int length = input.readVarInt();
        if (length < 0 || length > ProtocolLimits.MAX_FRAME_BYTES) throw new ProtocolException("Invalid frame length " + length);
        if (input.readableBytes() != length) throw new ProtocolException("Truncated or concatenated frame");
        return input.readRawBytes(length);
    }

    private ProtocolFraming() {}
}
