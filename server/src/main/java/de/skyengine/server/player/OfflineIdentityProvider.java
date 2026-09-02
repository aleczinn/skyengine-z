package de.skyengine.server.player;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.UUID;

public final class OfflineIdentityProvider implements IdentityProvider {
    @Override
    public PlayerIdentity authenticate(String requestedName, UUID requestedIdentity, boolean trustedLocal)
            throws IdentityException {
        String name = requestedName == null ? "" : requestedName.trim();
        if (!name.matches("[A-Za-z0-9_]{3,32}")) {
            throw new IdentityException("Username must contain 3-32 letters, digits or underscores");
        }
        // Offline mode has no authenticated account authority. Honour the client's ephemeral
        // launch identity so multiple local client processes with the same OS username can join.
        UUID uuid = requestedIdentity != null ? requestedIdentity
                : UUID.nameUUIDFromBytes(("OfflinePlayer:" + name.toLowerCase(Locale.ROOT))
                        .getBytes(StandardCharsets.UTF_8));
        return new PlayerIdentity(uuid, name);
    }
}
