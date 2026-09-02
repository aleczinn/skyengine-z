package de.skyengine.server.world;

import de.skyengine.shared.world.BlockEntitySnapshot;

import java.util.Objects;

/** Tick-owned block-entity state update, filtered by chunk interest before transmission. */
public record BlockEntityReplicationUpdate(String dimension, int chunkX, int chunkZ,
                                           BlockEntitySnapshot blockEntity) {
    public BlockEntityReplicationUpdate {
        Objects.requireNonNull(dimension);
        Objects.requireNonNull(blockEntity);
    }
}
