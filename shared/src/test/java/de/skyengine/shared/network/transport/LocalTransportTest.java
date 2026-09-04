package de.skyengine.shared.network.transport;

import de.skyengine.shared.network.DisconnectReason;
import de.skyengine.shared.network.PacketEnvelope;
import de.skyengine.shared.network.packets.CorePackets;
import org.junit.jupiter.api.Test;

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
}
