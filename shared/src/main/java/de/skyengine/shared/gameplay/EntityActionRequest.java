package de.skyengine.shared.gameplay;

/** Client intent directed at a replicated entity; the server validates reach and entity type. */
public record EntityActionRequest(long actionId, Action action, int networkEntityId) {
    public enum Action { ATTACK, INTERACT, PICK }

    public EntityActionRequest {
        if (actionId < 0 || action == null || networkEntityId <= 0) {
            throw new IllegalArgumentException("Invalid entity action");
        }
    }
}
