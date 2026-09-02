package de.skyengine.server.event;

import de.skyengine.server.player.PlayerIdentity;
import de.skyengine.shared.gameplay.BlockActionRequest;

public final class BlockActionEvent extends CancellableServerEvent {
    private final PlayerIdentity identity;
    private final BlockActionRequest request;
    public BlockActionEvent(PlayerIdentity identity, BlockActionRequest request) {
        this.identity = identity; this.request = request;
    }
    public PlayerIdentity identity() { return this.identity; }
    public BlockActionRequest request() { return this.request; }
}
