package de.skyengine.game.command;

import de.skyengine.game.world.block.entity.SimpleItemStorage;
import de.skyengine.game.world.World;

/** Die fuer Singleplayer-Befehle verfuegbare Spielumgebung. */
public record CommandContext(SimpleItemStorage inventory, World world) {

    public CommandContext(SimpleItemStorage inventory) {
        this(inventory, null);
    }
}
