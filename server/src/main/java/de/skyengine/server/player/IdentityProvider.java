package de.skyengine.server.player;

import java.util.UUID;

public interface IdentityProvider {
    PlayerIdentity authenticate(String requestedName, UUID requestedIdentity, boolean trustedLocal)
            throws IdentityException;

    final class IdentityException extends Exception {
        public IdentityException(String message) { super(message); }
    }
}
