package de.skyengine.game.world.item;

import de.skyengine.game.entity.EntityPlayer;
import de.skyengine.game.world.Dimension;

/**
 * Laufzeit-Kontext eines Item-Tooltips. Welt und Spieler dürfen null sein, etwa in Menüs ohne
 * geladene Welt; Provider müssen diesen Fall behandeln.
 */
public record TooltipContext(Dimension world, EntityPlayer player) {
}
