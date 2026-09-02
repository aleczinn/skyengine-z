package de.skyengine.shared.network;

import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

public final class ConnectionStateMachine {
    private final AtomicReference<ConnectionState> state = new AtomicReference<>(ConnectionState.HANDSHAKE);

    public ConnectionState state() { return this.state.get(); }

    public void transition(ConnectionState expected, ConnectionState next) {
        if (!allowed(expected, next)) throw new IllegalArgumentException("Illegal transition " + expected + " -> " + next);
        if (!this.state.compareAndSet(expected, next)) {
            throw new IllegalStateException("Expected " + expected + " but connection is " + this.state.get());
        }
    }

    public void close() { this.state.set(ConnectionState.CLOSED); }

    private static boolean allowed(ConnectionState from, ConnectionState to) {
        return switch (from) {
            case HANDSHAKE -> to == ConnectionState.LOGIN || to == ConnectionState.DISCONNECTING;
            case LOGIN -> to == ConnectionState.CONFIGURATION || to == ConnectionState.DISCONNECTING;
            case CONFIGURATION -> to == ConnectionState.JOINING || to == ConnectionState.DISCONNECTING;
            case JOINING -> to == ConnectionState.PLAY || to == ConnectionState.DISCONNECTING;
            case PLAY -> to == ConnectionState.DISCONNECTING;
            case DISCONNECTING -> to == ConnectionState.CLOSED;
            case CLOSED -> false;
        };
    }
}
