package de.skyengine.shared.network;

import de.skyengine.shared.network.packets.CorePackets;
import de.skyengine.shared.player.PlayerInputFrame;
import de.skyengine.shared.player.PlayerStateSnapshot;
import de.skyengine.shared.player.PlayerGameMode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MovementProtocolTest {
    @Test
    void inputUsesSequencedDeliveryAndBoundedAxes() throws Exception {
        PacketRegistry registry = CoreProtocol.createRegistry();
        PlayerInputFrame input = new PlayerInputFrame(9, 17, 0.5f, -1, 275, -30,
                PlayerInputFrame.JUMP | PlayerInputFrame.SPRINT
                        | PlayerInputFrame.SPRINT_TOGGLE_MODE | PlayerInputFrame.SPECTATOR_SPEED_UP);
        PacketEnvelope envelope = new PacketEnvelope(new CorePackets.PlayerInput(input), input.sequence());
        byte[] body = registry.encode(PacketDirection.CLIENT_TO_SERVER, ConnectionState.PLAY, envelope);
        DecodedPacket decoded = registry.decode(PacketDirection.CLIENT_TO_SERVER, ConnectionState.PLAY, body);
        CorePackets.PlayerInput packet = (CorePackets.PlayerInput) decoded.packet();
        assertEquals(9, decoded.sequence());
        assertEquals(DeliveryClass.UNRELIABLE_SEQUENCED, decoded.type().delivery());
        assertEquals(0.5f, packet.input().forward(), 1.0f / 127.0f);
        assertEquals(-1, packet.input().strafe());
        assertEquals(input.buttons(), packet.input().buttons());
    }

    @Test
    void nonFiniteAndOutOfRangeMovementDataCannotBeConstructed() {
        assertThrows(IllegalArgumentException.class,
                () -> new PlayerInputFrame(1, 1, Float.NaN, 0, 0, 0, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new PlayerInputFrame(1, 1, 0, 0, 0, 91, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new PlayerStateSnapshot(1, 1, "skyengine:overworld", Double.NaN, 0, 0,
                        0, 0, 0, 0, 0, false, PlayerGameMode.CREATIVE, 0));
    }

    @Test void authoritativeStateRoundtripCarriesGameModeAndFlightFlags() throws Exception {
        PacketRegistry registry = CoreProtocol.createRegistry();
        PlayerStateSnapshot state = new PlayerStateSnapshot(7, 4, "skyengine:overworld",
                1, 2, 3, 0.1, 0.2, 0.3, 45, -10, false,
                PlayerGameMode.SPECTATOR,
                de.skyengine.shared.player.PlayerMovementState.FLYING
                        | de.skyengine.shared.player.PlayerMovementState.NO_CLIP,
                20, 20, 5, 4, 1_000_002, 4.5F);
        byte[] body = registry.encode(PacketDirection.SERVER_TO_CLIENT, ConnectionState.PLAY,
                new PacketEnvelope(new CorePackets.PlayerState(state), 7));
        CorePackets.PlayerState decoded = (CorePackets.PlayerState) registry.decode(
                PacketDirection.SERVER_TO_CLIENT, ConnectionState.PLAY, body).packet();

        assertEquals(PlayerGameMode.SPECTATOR, decoded.state().gameMode());
        assertEquals(state.movementState(), decoded.state().movementState());
        assertEquals(1_000_002, decoded.state().vehicleEntityId());
        assertEquals(4.5F, decoded.state().spectatorFlySpeed());
    }
}
