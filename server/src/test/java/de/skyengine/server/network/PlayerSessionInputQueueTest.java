package de.skyengine.server.network;

import de.skyengine.shared.network.transport.LocalTransport;
import de.skyengine.shared.player.PlayerInputFrame;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PlayerSessionInputQueueTest {
    @Test
    void orderedInputsAreConsumedExactlyOnceInsteadOfOverwritingEachOther() {
        LocalTransport.Pair pair = LocalTransport.create();
        PlayerSession session = new PlayerSession(pair.server(), 1);
        for (int sequence = 1; sequence <= 4; sequence++) {
            session.enqueueSimulationInput(new PlayerInputFrame(sequence, sequence,
                    1, 0, 0, 0, 0));
        }

        assertEquals(4, session.pendingSimulationInputs());
        for (int sequence = 1; sequence <= 4; sequence++) {
            assertEquals(sequence, session.pollSimulationInput().sequence());
        }
        assertEquals(0, session.pendingSimulationInputs());
    }
}
