package de.skyengine.server.network;

import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.CorruptedFrameException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;

class VarIntFrameDecoderTest {
    @Test void rejectsNonCanonicalAndOversizedFrameLengths() {
        EmbeddedChannel nonCanonical = new EmbeddedChannel(new VarIntFrameDecoder(64));
        try {
            assertThrows(CorruptedFrameException.class,
                    () -> nonCanonical.writeInbound(Unpooled.wrappedBuffer(new byte[]{(byte) 0x80, 0})));
        } finally { nonCanonical.finishAndReleaseAll(); }
        EmbeddedChannel oversized = new EmbeddedChannel(new VarIntFrameDecoder(64));
        try {
            assertThrows(CorruptedFrameException.class,
                    () -> oversized.writeInbound(Unpooled.wrappedBuffer(new byte[]{65})));
        } finally { oversized.finishAndReleaseAll(); }
    }
}
