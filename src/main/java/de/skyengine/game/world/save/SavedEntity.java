package de.skyengine.game.world.save;

import de.skyengine.game.world.block.entity.DataTag;

/** Persistenter Entity-Snapshot eines Chunks (Hanging-Entities und Minecarts). */
public record SavedEntity(String typeId, DataTag tag) {}
