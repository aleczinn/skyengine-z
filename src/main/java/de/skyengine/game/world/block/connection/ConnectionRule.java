package de.skyengine.game.world.block.connection;

import de.skyengine.game.world.World;
import de.skyengine.game.world.block.Direction;
import de.skyengine.game.world.block.state.BlockState;

/**
 * Entscheidet, ob sich ein Block in eine Richtung mit seinem Nachbarn verbindet.
 * Austauschbar je Anwendungsfall (Zaun, Wall, Pipe, Cable, Netzwerk).
 */
@FunctionalInterface
public interface ConnectionRule {
    boolean connects(World world, int x, int y, int z, Direction dir, BlockState self, BlockState neighbor);
}
