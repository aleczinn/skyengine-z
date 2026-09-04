package de.skyengine.client.network;

/**
 * Canonical client gameplay session used for both LocalTransport and TCPTransport.
 *
 * <p>The superclass name remains as a source-compatible facade for menu/status code. Gameplay
 * must depend on this transport-neutral type so integrated and dedicated servers cannot acquire
 * separate client implementations again.
 */
public final class ClientGameSession extends ClientMultiplayerConnection {
}
