package de.skyengine.shared.network.transport;

import de.skyengine.shared.network.DisconnectReason;
import de.skyengine.shared.network.PacketEnvelope;
import de.skyengine.shared.network.packets.CorePackets;
import de.skyengine.shared.world.BlockChange;
import de.skyengine.shared.world.ImmutableChunkColumnData;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalTransportTest {
    @Test
    void transportIsBoundedAndPreservesPacketOrder() {
        LocalTransport.Pair pair = LocalTransport.create(16);
        for (int i = 0; i < 16; i++) {
            assertTrue(pair.client().send(new PacketEnvelope(new CorePackets.ClientReady(i))));
        }
        assertFalse(pair.client().send(new PacketEnvelope(new CorePackets.ClientReady(17))));
        for (int i = 0; i < 16; i++) {
            CorePackets.ClientReady packet = assertInstanceOf(CorePackets.ClientReady.class,
                    pair.server().pollInbound().packet());
            assertEquals(i, packet.lastAppliedChunkBatch());
        }
        assertNull(pair.server().pollInbound());
    }

    @Test
    void disconnectReasonCanBeObservedByPeer() {
        LocalTransport.Pair pair = LocalTransport.create();
        pair.server().disconnect(DisconnectReason.KICKED, "test");
        CorePackets.Disconnect packet = assertInstanceOf(CorePackets.Disconnect.class,
                pair.client().pollInbound().packet());
        assertEquals(DisconnectReason.KICKED, packet.reason());
        assertFalse(pair.client().open());
        assertFalse(pair.server().open());
    }

    @Test
    void reliableGameplayKeepsPriorityHeadroomWhenChunkQueueIsFull() {
        LocalTransport.Pair pair = LocalTransport.create(16);
        BatchTransport server = (BatchTransport) pair.server();

        assertTrue(server.sendBatch(chunkBatch(1)));
        assertTrue(server.sendBatch(chunkBatch(2)));
        assertFalse(server.sendBatch(chunkBatch(3)));

        CorePackets.BlockUpdate update = new CorePackets.BlockUpdate(
                "skyengine:overworld", 0, 0, 1, new BlockChange(0, 64, 0, 0));
        assertTrue(pair.server().send(new PacketEnvelope(update)));
        assertInstanceOf(CorePackets.BlockUpdate.class, pair.client().pollInbound().packet());
    }

    @Test
    void queuedChunkBatchCanBeCancelledAtomically() {
        LocalTransport.Pair pair = LocalTransport.create(16);
        BatchTransport server = (BatchTransport) pair.server();
        assertTrue(server.sendBatch(chunkBatch(7)));

        assertTrue(server.cancelBatch(7));
        assertNull(pair.client().pollInbound());
    }

    private static List<PacketEnvelope> chunkBatch(long batchId) {
        int[] column = new int[32 * 32];
        int[] tint = new int[33 * 33];
        ImmutableChunkColumnData snapshot = ImmutableChunkColumnData.takeOwnership(
                "skyengine:overworld", 0, 0, batchId, List.of(), column, tint,
                tint.clone(), column.clone(), List.of());
        return List.of(
                new PacketEnvelope(new CorePackets.ChunkBatchStart(
                        batchId, batchId, 1, snapshot.dimension(), 0, 0, 1)),
                new PacketEnvelope(new CorePackets.ChunkColumnData(batchId, snapshot)),
                new PacketEnvelope(new CorePackets.ChunkBatchEnd(batchId)));
    }
}
