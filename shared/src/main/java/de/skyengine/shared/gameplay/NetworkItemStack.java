package de.skyengine.shared.gameplay;

import java.util.Objects;

public record NetworkItemStack(int itemId, int count, byte[] components) {
    public static final int MAX_COMPONENT_BYTES = 64 * 1024;
    public NetworkItemStack {
        if (itemId < 0 || count < 0 || count > 1_000_000) throw new IllegalArgumentException("Invalid item stack");
        components = components == null ? new byte[0] : components.clone();
        if (components.length > MAX_COMPONENT_BYTES) throw new IllegalArgumentException("Item components too large");
        if (count == 0 && itemId != 0) throw new IllegalArgumentException("Empty stacks use item ID zero");
    }
    @Override public byte[] components() { return this.components.clone(); }
    public static NetworkItemStack empty() { return new NetworkItemStack(0, 0, null); }
}
