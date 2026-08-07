package de.skyengine.game.world.save;

import de.skyengine.game.world.block.entity.DataTag;

/** Persistenter Entity-Snapshot eines Chunks; derzeit bewusst nur Item Frames. */
public record SavedEntity(String typeId, DataTag tag) {}
