package de.skyengine.game.world.item;

import de.skyengine.game.entity.EntityPlayer;
import de.skyengine.game.world.World;

/**
 * Laufzeit-Kontext eines Item-Tooltips. Welt und Spieler dürfen null sein, etwa in Menüs ohne
 * geladene Welt; Provider müssen diesen Fall behandeln.
 */
public record TooltipContext(World world, EntityPlayer player) {
}
