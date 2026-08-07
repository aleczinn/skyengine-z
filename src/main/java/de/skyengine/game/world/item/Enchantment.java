package de.skyengine.game.world.item;

import de.skyengine.game.world.block.Identifier;

/** Registrierte, datenstabile Verzauberung. */
public record Enchantment(Identifier id, int maxLevel) {

    public Enchantment {
        if (maxLevel < 1) throw new IllegalArgumentException("maxLevel muss positiv sein");
    }
}
