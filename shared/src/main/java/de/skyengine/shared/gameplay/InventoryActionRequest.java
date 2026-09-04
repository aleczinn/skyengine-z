package de.skyengine.shared.gameplay;

import java.util.Objects;

public record InventoryActionRequest(long transactionId, int containerId, int sourceSlot,
                                     int targetSlot, Action action, int button,
                                     NetworkItemStack offeredStack) {
    public InventoryActionRequest {
        if (transactionId < 0 || containerId < 0 || sourceSlot < -3 || sourceSlot > 4095
                || targetSlot < -1 || targetSlot > 4095 || button < 0 || button > 64) {
            throw new IllegalArgumentException("Invalid inventory action");
        }
        Objects.requireNonNull(action);
        offeredStack = offeredStack == null ? NetworkItemStack.empty() : offeredStack;
    }
    public InventoryActionRequest(long transactionId, int containerId, int sourceSlot,
                                  int targetSlot, Action action, int button) {
        this(transactionId, containerId, sourceSlot, targetSlot, action, button,
                NetworkItemStack.empty());
    }
    public enum Action { PICKUP, QUICK_MOVE, SWAP, DROP, DRAG, CLONE }
}
