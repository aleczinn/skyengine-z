package de.skyengine.server.world;

public record EntityActionOutcome(long actionId, boolean accepted, String message,
                                  boolean inventoryChanged) {
    public static EntityActionOutcome rejected(long id, String message) {
        return new EntityActionOutcome(id, false, message, false);
    }
}
