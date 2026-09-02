package de.skyengine.server.world;

import java.util.List;

public record BlockActionOutcome(long actionId, boolean accepted, String message,
                                 List<ChunkBlockChanges> chunkChanges, boolean inventoryChanged,
                                 ContainerOpenData openedContainer) {
    public BlockActionOutcome { chunkChanges = List.copyOf(chunkChanges); }
    public BlockActionOutcome(long actionId, boolean accepted, String message,
                              List<ChunkBlockChanges> chunkChanges, boolean inventoryChanged) {
        this(actionId, accepted, message, chunkChanges, inventoryChanged, null);
    }
    public static BlockActionOutcome rejected(long id, String message) {
        return new BlockActionOutcome(id, false, message, List.of(), false, null);
    }
}
