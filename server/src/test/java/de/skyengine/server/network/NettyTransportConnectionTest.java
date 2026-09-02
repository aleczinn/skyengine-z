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
import de.skyengine.shared.world.ChunkColumnSnapshot;
import de.skyengine.shared.world.ChunkSectionSnapshot;
import de.skyengine.shared.world.LightPlane;

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

    @Test void largeChunkBatchIsSplitIntoBoundedTransportFragments() throws Exception {
        var registry = CoreProtocol.createRegistry();
        EmbeddedChannel channel = new EmbeddedChannel();
        List<Runnable> encoderTasks = new ArrayList<>();
        NettyTransportConnection connection = new NettyTransportConnection(channel, registry, true,
                encoderTasks::add);
        advanceToPlay(connection);
        ChunkColumnSnapshot chunk = largeChunk();
        assertTrue(connection.sendBatch(List.of(
                new PacketEnvelope(new CorePackets.ChunkBatchStart(3, chunk.dimension(), 0, 0, 1)),
                new PacketEnvelope(new CorePackets.ChunkColumnData(3, chunk)),
                new PacketEnvelope(new CorePackets.ChunkBatchEnd(3)))));
        encoderTasks.removeFirst().run();

        List<CorePackets.ChunkColumnFragment> fragments = new ArrayList<>();
        for (int flush = 0; flush < 16 && connection.outboundSize() > 0; flush++) {
            connection.flushOutbound(64 * 1024);
            ByteBuf framed;
            while ((framed = channel.readOutbound()) != null) {
                byte[] bytes = new byte[framed.readableBytes()];
                framed.readBytes(bytes).release();
                Object packet = registry.decode(PacketDirection.SERVER_TO_CLIENT, ConnectionState.PLAY,
                        ProtocolFraming.unframe(bytes)).packet();
                if (packet instanceof CorePackets.ChunkColumnFragment fragment) fragments.add(fragment);
            }
        }

        assertTrue(fragments.size() > 1);
        assertTrue(fragments.stream().allMatch(fragment ->
                fragment.data().length <= CorePackets.ChunkColumnFragment.MAX_FRAGMENT_BYTES));
        assertEquals(fragments.size(), fragments.getFirst().fragmentCount());
        connection.close();
        channel.finishAndReleaseAll();
    }

    private static ChunkColumnSnapshot largeChunk() {
        List<ChunkSectionSnapshot> sections = new ArrayList<>();
        byte[] light = new byte[LightPlane.PACKED_BYTES];
        for (int sectionY = 0; sectionY < 4; sectionY++) {
            sections.add(new ChunkSectionSnapshot(sectionY, ChunkSectionSnapshot.VOLUME,
                    new int[] {1}, 0, new long[0],
                    new LightPlane(LightPlane.Mode.PACKED_NIBBLES, light),
                    new LightPlane(LightPlane.Mode.PACKED_NIBBLES, light)));
        }
        return new ChunkColumnSnapshot("skyengine:overworld", 0, 0, 1, sections,
                new int[ChunkColumnSnapshot.COLUMN_CELLS],
                new int[ChunkColumnSnapshot.TINT_CORNERS],
                new int[ChunkColumnSnapshot.TINT_CORNERS],
                new int[ChunkColumnSnapshot.COLUMN_CELLS]);
    }

    private static void advanceToPlay(NettyTransportConnection connection) {
        connection.transitionState(ConnectionState.HANDSHAKE, ConnectionState.LOGIN);
        connection.transitionState(ConnectionState.LOGIN, ConnectionState.CONFIGURATION);
        connection.transitionState(ConnectionState.CONFIGURATION, ConnectionState.JOINING);
        connection.transitionState(ConnectionState.JOINING, ConnectionState.PLAY);
    }
}
