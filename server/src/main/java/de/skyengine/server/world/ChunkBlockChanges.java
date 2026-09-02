package de.skyengine.server.world;

import de.skyengine.shared.world.BlockChange;

import java.util.List;

public record ChunkBlockChanges(String dimension, int chunkX, int chunkZ, long revision,
                                List<BlockChange> changes) {
    public ChunkBlockChanges { changes = List.copyOf(changes); }
}
