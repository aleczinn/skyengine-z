package de.skyengine.shared.network.transport;

import de.skyengine.shared.network.CoreProtocol;
import de.skyengine.shared.network.PacketDirection;
import de.skyengine.shared.network.PacketEnvelope;
import de.skyengine.shared.network.packets.CorePackets;
import de.skyengine.shared.player.PlayerInputFrame;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SimulatedTransportConnectionTest {
    @Test
    void deterministicLatencyAndUnreliableLossDoNotAffectReliablePackets() {
        AtomicLong clock = new AtomicLong();
        LocalTransport.Pair pair = LocalTransport.create();
        var config = new NetworkSimulationConfig(100, 0, 0, 1, 0, 0, 123);
        SimulatedTransportConnection simulated = new SimulatedTransportConnection(pair.client(),
                CoreProtocol.createRegistry(), PacketDirection.CLIENT_TO_SERVER, config, clock::get);
        assertTrue(simulated.send(new PacketEnvelope(new CorePackets.Handshake(1, "test"))));
        simulated.pump();
        assertNull(pair.server().pollInbound());
        clock.set(99_000_000L);
        simulated.pump();
        assertNull(pair.server().pollInbound());
        clock.set(100_000_000L);
        simulated.pump();
        assertNotNull(pair.server().pollInbound());

        var movement = new CorePackets.PlayerInput(new PlayerInputFrame(1, 1, 1, 0, 0, 0, 0));
        assertTrue(simulated.send(new PacketEnvelope(movement, 1)));
        clock.set(1_000_000_000L);
        simulated.pump();
        assertNull(pair.server().pollInbound());
    }
}
