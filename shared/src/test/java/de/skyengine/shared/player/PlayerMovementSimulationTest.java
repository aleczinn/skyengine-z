package de.skyengine.shared.player;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerMovementSimulationTest {
    @Test void creativeFlightAndGameModeCycleAreDeterministic() {
        PlayerStateSnapshot initial = state(PlayerGameMode.CREATIVE, 0);
        PlayerInputFrame takeoff = input(1, PlayerInputFrame.JUMP | PlayerInputFrame.TOGGLE_FLY);
        PlayerStateSnapshot predicted = PlayerMovementSimulation.simulate(initial, takeoff, 1,
                (x, z, fallback) -> 65);
        PlayerStateSnapshot authoritative = PlayerMovementSimulation.simulate(initial, takeoff, 1,
                (x, z, fallback) -> 65);

        assertEquals(authoritative, predicted);
        assertTrue((predicted.movementState() & PlayerMovementState.FLYING) != 0);
        assertTrue(predicted.y() > initial.y());

        PlayerStateSnapshot spectator = PlayerMovementSimulation.simulate(predicted,
                input(2, PlayerInputFrame.CYCLE_GAME_MODE), 2, (x, z, fallback) -> 65);
        assertEquals(PlayerGameMode.SPECTATOR, spectator.gameMode());
        assertTrue((spectator.movementState() & PlayerMovementState.NO_CLIP) != 0);
    }

    @Test void survivalCannotRetainCreativeFlight() {
        PlayerStateSnapshot flying = state(PlayerGameMode.CREATIVE, PlayerMovementState.FLYING);
        PlayerStateSnapshot survival = PlayerMovementSimulation.withGameMode(flying,
                PlayerGameMode.SURVIVAL, 4);
        assertFalse((survival.movementState() & PlayerMovementState.FLYING) != 0);
        assertFalse((survival.movementState() & PlayerMovementState.NO_CLIP) != 0);
    }

    private static PlayerStateSnapshot state(PlayerGameMode mode, int movement) {
        return new PlayerStateSnapshot(0, 0, "skyengine:overworld", 0.5, 65, 0.5,
                0, 0, 0, 0, 0, true, mode, movement);
    }

    private static PlayerInputFrame input(long sequence, int buttons) {
        return new PlayerInputFrame(sequence, sequence, 0, 0, 0, 0, buttons);
    }
}
