package de.skyengine.game.world.item;

import de.skyengine.game.world.block.Identifier;

/**
 * Einfaches Material-Item ohne Sonderverhalten (Barren, Stock, Kohle, Edelstein). Wird aus
 * {@code game/items/*.json} erzeugt; das Rendering läuft über {@link #getIconTexture()} —
 * flaches Sprite im Inventar, extrudiert in der Hand (wie bei Eimern und Essen).
 */
public final class SimpleItem extends Item {

    private final String iconTexture;

    public SimpleItem(Identifier id, int maxStackSize, String iconTexture) {
        super(id, maxStackSize);
        this.iconTexture = iconTexture;
    }

    @Override
    public String getIconTexture() {
        return iconTexture;
    }
}
