package de.skyengine.server.player;

import java.util.Objects;
import java.util.UUID;

public record PlayerIdentity(UUID uuid, String name) {
    public PlayerIdentity {
        Objects.requireNonNull(uuid);
        Objects.requireNonNull(name);
    }
}
