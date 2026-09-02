package de.skyengine.shared.network;

public enum ConnectionState {
    HANDSHAKE,
    LOGIN,
    CONFIGURATION,
    JOINING,
    PLAY,
    DISCONNECTING,
    CLOSED
}
