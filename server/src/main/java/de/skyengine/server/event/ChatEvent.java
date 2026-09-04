package de.skyengine.server.event;

import de.skyengine.server.player.PlayerIdentity;

public final class ChatEvent extends CancellableServerEvent {
    private final PlayerIdentity identity;
    private String message;
    public ChatEvent(PlayerIdentity identity, String message) { this.identity = identity; this.message = message; }
    public PlayerIdentity identity() { return this.identity; }
    public String message() { return this.message; }
    public void message(String message) { this.message = message; }
}
