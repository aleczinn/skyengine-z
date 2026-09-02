package de.skyengine.shared.network;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ConnectionStateMachineTest {
    @Test
    void onlyCanonicalLifecycleTransitionsAreAccepted() {
        ConnectionStateMachine states = new ConnectionStateMachine();
        states.transition(ConnectionState.HANDSHAKE, ConnectionState.LOGIN);
        states.transition(ConnectionState.LOGIN, ConnectionState.CONFIGURATION);
        states.transition(ConnectionState.CONFIGURATION, ConnectionState.JOINING);
        states.transition(ConnectionState.JOINING, ConnectionState.PLAY);
        states.transition(ConnectionState.PLAY, ConnectionState.DISCONNECTING);
        states.transition(ConnectionState.DISCONNECTING, ConnectionState.CLOSED);
        assertEquals(ConnectionState.CLOSED, states.state());
    }

    @Test
    void skippedAndRacingTransitionsAreRejected() {
        ConnectionStateMachine states = new ConnectionStateMachine();
        assertThrows(IllegalArgumentException.class,
                () -> states.transition(ConnectionState.HANDSHAKE, ConnectionState.PLAY));
        assertThrows(IllegalStateException.class,
                () -> states.transition(ConnectionState.LOGIN, ConnectionState.CONFIGURATION));
    }
}
