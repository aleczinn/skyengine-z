package de.skyengine.server.event;

import de.skyengine.server.player.PlayerIdentity;
import java.net.SocketAddress;

public final class PlayerLoginEvent extends CancellableServerEvent {
    private final PlayerIdentity identity;
    private final SocketAddress remoteAddress;
    public PlayerLoginEvent(PlayerIdentity identity, SocketAddress remoteAddress) {
        this.identity = identity; this.remoteAddress = remoteAddress;
    }
    public PlayerIdentity identity() { return this.identity; }
    public SocketAddress remoteAddress() { return this.remoteAddress; }
}
