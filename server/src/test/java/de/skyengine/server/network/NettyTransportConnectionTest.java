package de.skyengine.server.network;

import de.skyengine.shared.network.ConnectionState;
import de.skyengine.shared.network.CoreProtocol;
import de.skyengine.shared.network.PacketDirection;
import de.skyengine.shared.network.PacketEnvelope;
import de.skyengine.shared.network.ProtocolFraming;
import de.skyengine.shared.network.packets.CorePackets;
import io.netty.buffer.ByteBuf;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NettyTransportConnectionTest {
    @Test void batchEncodingRunsOffCallerAndPreservesPacketOrder() throws Exception {
        var registry = CoreProtocol.createRegistry();
        EmbeddedChannel channel = new EmbeddedChannel();
        List<Runnable> encoderTasks = new ArrayList<>();
        NettyTransportConnection connection = new NettyTransportConnection(channel, registry, true,
                encoderTasks::add);
        advanceToPlay(connection);
        UUID sender = UUID.randomUUID();
        List<PacketEnvelope> packets = List.of(
                new PacketEnvelope(new CorePackets.ChatMessage(sender, "test", 1, "one")),
                new PacketEnvelope(new CorePackets.ChatMessage(sender, "test", 1, "two")),
                new PacketEnvelope(new CorePackets.ChatMessage(sender, "test", 1, "three")));

        assertTrue(connection.sendBatch(packets));
        assertEquals(1, encoderTasks.size());
        assertEquals(3, connection.outboundSize());
        encoderTasks.removeFirst().run();
        connection.flushOutbound(64 * 1024);

        for (String expected : List.of("one", "two", "three")) {
            ByteBuf framed = channel.readOutbound();
            byte[] bytes = new byte[framed.readableBytes()];
            framed.readBytes(bytes).release();
            CorePackets.ChatMessage decoded = (CorePackets.ChatMessage) registry.decode(
                    PacketDirection.SERVER_TO_CLIENT, ConnectionState.PLAY,
                    ProtocolFraming.unframe(bytes)).packet();
            assertEquals(expected, decoded.message());
        }
        connection.close();
        channel.finishAndReleaseAll();
    }

    private static void advanceToPlay(NettyTransportConnection connection) {
        connection.transitionState(ConnectionState.HANDSHAKE, ConnectionState.LOGIN);
        connection.transitionState(ConnectionState.LOGIN, ConnectionState.CONFIGURATION);
        connection.transitionState(ConnectionState.CONFIGURATION, ConnectionState.JOINING);
        connection.transitionState(ConnectionState.JOINING, ConnectionState.PLAY);
    }
}
