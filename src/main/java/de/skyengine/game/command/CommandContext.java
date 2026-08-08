package de.skyengine.game.command;

import de.skyengine.game.world.block.entity.SimpleItemStorage;

/** Die fuer Singleplayer-Befehle verfuegbare Spielumgebung. */
public record CommandContext(SimpleItemStorage inventory) {
}
