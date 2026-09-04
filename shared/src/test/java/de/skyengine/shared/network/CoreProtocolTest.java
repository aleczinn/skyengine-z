package de.skyengine.shared.network;

import de.skyengine.shared.network.packets.CorePackets;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CoreProtocolTest {
    @Test
    void packetRoundTripChecksDirectionStateChannelAndPayload() throws Exception {
        PacketRegistry registry = CoreProtocol.createRegistry();
        CorePackets.Handshake source = new CorePackets.Handshake(1, "0.0.16-alpha");
        byte[] body = registry.encode(PacketDirection.CLIENT_TO_SERVER, ConnectionState.HANDSHAKE,
                new PacketEnvelope(source));
        DecodedPacket decoded = registry.decode(PacketDirection.CLIENT_TO_SERVER,
                ConnectionState.HANDSHAKE, body);

        assertEquals(source, decoded.packet());
        assertEquals(LogicalChannel.CONTROL, decoded.type().channel());
        assertEquals(DeliveryClass.RELIABLE_ORDERED, decoded.type().delivery());
        assertThrows(ProtocolException.class, () -> registry.decode(PacketDirection.SERVER_TO_CLIENT,
                ConnectionState.HANDSHAKE, body));
        assertThrows(ProtocolException.class, () -> registry.encode(PacketDirection.CLIENT_TO_SERVER,
                ConnectionState.PLAY, new PacketEnvelope(source)));
    }

    @Test
    void fixedSizeFingerprintRoundTripsWithoutExposingMutableStorage() throws Exception {
        PacketRegistry registry = CoreProtocol.createRegistry();
        byte[] hash = new byte[32];
        hash[7] = 42;
        CorePackets.RegistryFingerprint packet = new CorePackets.RegistryFingerprint(hash);
        hash[7] = 0;
        byte[] body = registry.encode(PacketDirection.SERVER_TO_CLIENT, ConnectionState.CONFIGURATION,
                new PacketEnvelope(packet));
        CorePackets.RegistryFingerprint decoded = assertInstanceOf(CorePackets.RegistryFingerprint.class,
                registry.decode(PacketDirection.SERVER_TO_CLIENT, ConnectionState.CONFIGURATION, body).packet());
        byte[] expected = new byte[32];
        expected[7] = 42;
        assertArrayEquals(expected, decoded.sha256());
    }

    @Test
    void framingRejectsTruncationConcatenationAndOversize() throws Exception {
        byte[] body = {1, 2, 3};
        assertArrayEquals(body, ProtocolFraming.unframe(ProtocolFraming.frame(body)));
        assertThrows(ProtocolException.class, () -> ProtocolFraming.unframe(new byte[] {3, 1, 2}));
        assertThrows(ProtocolException.class, () -> ProtocolFraming.unframe(new byte[] {1, 1, 2}));
        assertThrows(ProtocolException.class,
                () -> ProtocolFraming.frame(new byte[ProtocolLimits.MAX_FRAME_BYTES + 1]));
    }

    @Test
    void collectionLimitsAreEnforcedByCodec() {
        PacketRegistry registry = CoreProtocol.createRegistry();
        CorePackets.HandshakeAccepted packet = new CorePackets.HandshakeAccepted(1, "test",
                java.util.Collections.nCopies(17, "none"));
        assertThrows(ProtocolException.class, () -> registry.encode(PacketDirection.SERVER_TO_CLIENT,
                ConnectionState.HANDSHAKE, new PacketEnvelope(packet)));
    }

    @Test
    void applicationStatusResponseRoundTripsInHandshakeState() throws Exception {
        PacketRegistry registry = CoreProtocol.createRegistry();
        CorePackets.ServerStatusResponse source = new CorePackets.ServerStatusResponse(
                42L, 1, "0.0.16-alpha", "Test Server", 3, 8);
        byte[] body = registry.encode(PacketDirection.SERVER_TO_CLIENT, ConnectionState.HANDSHAKE,
                new PacketEnvelope(source));
        CorePackets.ServerStatusResponse decoded = assertInstanceOf(CorePackets.ServerStatusResponse.class,
                registry.decode(PacketDirection.SERVER_TO_CLIENT, ConnectionState.HANDSHAKE, body).packet());
        assertEquals(source, decoded);
    }
}
