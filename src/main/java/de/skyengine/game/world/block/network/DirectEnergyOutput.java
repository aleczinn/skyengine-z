package de.skyengine.game.world.block.network;

import de.skyengine.game.world.block.Direction;

/** Optional policy for machines whose direct neighbour output can be disabled. */
public interface DirectEnergyOutput {
    boolean allowsDirectEnergyOutput(Direction side);
}
