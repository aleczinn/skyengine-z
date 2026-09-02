package de.skyengine.server.world;

import java.util.List;
import de.skyengine.shared.gameplay.AuthoritativeBlockCorrection;

public record BlockActionOutcome(long actionId, boolean accepted, String message,
                                 List<ChunkBlockChanges> chunkChanges, boolean inventoryChanged,
                                 ContainerOpenData openedContainer,
                                 List<AuthoritativeBlockCorrection> corrections) {
    public BlockActionOutcome {
        chunkChanges = List.copyOf(chunkChanges);
        corrections = List.copyOf(corrections);
    }
    public BlockActionOutcome(long actionId, boolean accepted, String message,
                              List<ChunkBlockChanges> chunkChanges, boolean inventoryChanged) {
        this(actionId, accepted, message, chunkChanges, inventoryChanged, null, List.of());
    }
    public BlockActionOutcome(long actionId, boolean accepted, String message,
                              List<ChunkBlockChanges> chunkChanges, boolean inventoryChanged,
                              ContainerOpenData openedContainer) {
        this(actionId, accepted, message, chunkChanges, inventoryChanged, openedContainer, List.of());
    }
    public static BlockActionOutcome rejected(long id, String message) {
        return new BlockActionOutcome(id, false, message, List.of(), false, null, List.of());
    }
    public static BlockActionOutcome rejected(long id, String message,
                                              List<AuthoritativeBlockCorrection> corrections) {
        return new BlockActionOutcome(id, false, message, List.of(), false, null, corrections);
    }
}
