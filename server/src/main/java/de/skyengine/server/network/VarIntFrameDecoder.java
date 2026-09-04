package de.skyengine.server.network;

import de.skyengine.shared.network.ProtocolException;
import de.skyengine.shared.network.ProtocolLimits;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.handler.codec.CorruptedFrameException;

import java.util.List;

/** Netty decoder for the protocol's bounded VarInt length prefix. */
public final class VarIntFrameDecoder extends ByteToMessageDecoder {
    private final int maximumFrameBytes;

    public VarIntFrameDecoder(int maximumFrameBytes) {
        this.maximumFrameBytes = Math.min(maximumFrameBytes, ProtocolLimits.MAX_FRAME_BYTES);
    }

    @Override
    protected void decode(ChannelHandlerContext context, ByteBuf input, List<Object> output) {
        input.markReaderIndex();
        int length = 0;
        for (int i = 0; i < 5; i++) {
            if (!input.isReadable()) {
                input.resetReaderIndex();
                return;
            }
            int current = input.readUnsignedByte();
            length |= (current & 0x7F) << (i * 7);
            if ((current & 0x80) == 0) {
                if (i == 4 && (current & 0xF0) != 0) throw new CorruptedFrameException("VarInt overflow");
                if (i > 0 && (current & 0x7F) == 0) {
                    throw new CorruptedFrameException("Non-canonical frame VarInt");
                }
                if (length < 0 || length > this.maximumFrameBytes) {
                    throw new CorruptedFrameException("Frame exceeds " + this.maximumFrameBytes + " bytes");
                }
                if (input.readableBytes() < length) {
                    input.resetReaderIndex();
                    return;
                }
                output.add(input.readRetainedSlice(length));
                return;
            }
        }
        throw new CorruptedFrameException("Frame VarInt exceeds five bytes");
    }
}
