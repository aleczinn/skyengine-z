package de.skyengine.server.world;

import de.skyengine.shared.gameplay.NetworkItemStack;

import java.util.List;

public record InventoryActionOutcome(long transactionId, boolean accepted, String message,
                                     int containerId, int revision, List<NetworkItemStack> content,
                                     NetworkItemStack carried) {
    public InventoryActionOutcome { content = List.copyOf(content); }
    public static InventoryActionOutcome rejected(long id, String message) {
        return new InventoryActionOutcome(id, false, message, 0, 0, List.of(), NetworkItemStack.empty());
    }
}
