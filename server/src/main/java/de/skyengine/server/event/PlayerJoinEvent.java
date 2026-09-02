package de.skyengine.server.event;

import de.skyengine.server.player.PlayerIdentity;
public record PlayerJoinEvent(PlayerIdentity identity, int entityId) {}
