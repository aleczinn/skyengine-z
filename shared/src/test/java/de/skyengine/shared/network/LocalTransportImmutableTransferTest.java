package de.skyengine.shared.network;

import de.skyengine.shared.network.packets.CorePackets;
import de.skyengine.shared.network.transport.BatchTransport;
import de.skyengine.shared.network.transport.LocalTransport;
import de.skyengine.shared.world.ImmutableChunkColumnData;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalTransportImmutableTransferTest {
    @Test
    void localBatchTransfersTheSameImmutableRevisionWithoutWireCopies() {
        LocalTransport.Pair pair = LocalTransport.create(32);
        int[] column = new int[32 * 32];
        int[] tint = new int[33 * 33];
        ImmutableChunkColumnData snapshot = ImmutableChunkColumnData.takeOwnership(
                "skyengine:overworld", 2, 3, 4, List.of(), column, tint, tint.clone(),
                column.clone(), List.of());
        CorePackets.ChunkColumnData data = new CorePackets.ChunkColumnData(1, snapshot);

        assertTrue(((BatchTransport) pair.server()).sendBatch(List.of(new PacketEnvelope(data))));
        CorePackets.ChunkColumnData received =
                (CorePackets.ChunkColumnData) pair.client().pollInbound().packet();
        assertSame(snapshot, received.chunk());
    }
}
