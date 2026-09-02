package de.skyengine.shared.network;

public enum DisconnectReason {
    TIMEOUT,
    CLIENT_QUIT,
    SERVER_STOP,
    KICKED,
    PROTOCOL_MISMATCH,
    PACK_MISMATCH,
    INVALID_PACKET,
    LOGIN_FAILED,
    DUPLICATE_LOGIN,
    INTERNAL_ERROR
}
