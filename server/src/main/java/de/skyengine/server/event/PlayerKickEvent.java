package de.skyengine.server.event;

import de.skyengine.server.player.PlayerIdentity;
public record PlayerKickEvent(PlayerIdentity identity, String message) {}
