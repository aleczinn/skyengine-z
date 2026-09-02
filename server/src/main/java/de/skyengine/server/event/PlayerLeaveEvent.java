package de.skyengine.server.event;

import de.skyengine.server.player.PlayerIdentity;
import de.skyengine.shared.network.DisconnectReason;
public record PlayerLeaveEvent(PlayerIdentity identity, int entityId, DisconnectReason reason) {}
