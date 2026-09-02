package de.skyengine.client.network;

import de.skyengine.shared.player.PlayerInputFrame;
import de.skyengine.shared.player.PlayerGameMode;
import de.skyengine.shared.player.PlayerStateSnapshot;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ClientPredictionControllerTest {
    @Test
    void acknowledgementDropsConfirmedInputsAndReplaysOnlyPendingOnes() {
        ClientPredictionController controller = new ClientPredictionController(state(0, 0, 0),
                (state, input) -> state(input.clientTick(), input.sequence(), state.x() + input.forward()));
        controller.submit(input(1));
        controller.submit(input(2));
        controller.submit(input(3));
        assertEquals(3, controller.predicted().x());

        ClientPredictionController.Reconciliation reconciliation = controller.reconcile(state(2, 2, 1.8));
        assertEquals(1, reconciliation.replayedInputs());
        assertEquals(2.8, controller.predicted().x(), 1e-9);
        assertEquals(1, controller.pendingInputs());
        assertFalse(reconciliation.hardCorrection());
    }

    @Test
    void remoteSnapshotsInterpolateAcrossYawWrap() {
        RemotePlayerInterpolationBuffer buffer = new RemotePlayerInterpolationBuffer();
        buffer.add(new PlayerStateSnapshot(10, 0, "skyengine:overworld", 0, 0, 0,
                0, 0, 0, 350, 0, true, PlayerGameMode.CREATIVE, 0));
        buffer.add(new PlayerStateSnapshot(12, 0, "skyengine:overworld", 2, 4, 6,
                0, 0, 0, 10, 0, true, PlayerGameMode.CREATIVE, 0));
        PlayerStateSnapshot sampled = buffer.sample(11);
        assertEquals(1, sampled.x(), 1e-9);
        assertEquals(360, sampled.yaw(), 1e-6);
    }

    private static PlayerInputFrame input(long sequence) {
        return new PlayerInputFrame(sequence, sequence, 1, 0, 0, 0, 0);
    }

    private static PlayerStateSnapshot state(long tick, long sequence, double x) {
        return new PlayerStateSnapshot(tick, sequence, "skyengine:overworld", x, 0, 0,
                0, 0, 0, 0, 0, true, PlayerGameMode.CREATIVE, 0);
    }
}
