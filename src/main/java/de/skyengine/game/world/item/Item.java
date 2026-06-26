package de.skyengine.game.world.item;

import de.skyengine.game.world.block.Identifier;

/**
 * Ein registrierbarer Gegenstand. Basisklasse - Blöcke bekommen automatisch ein {@link BlockItem}
 * mit derselben {@link Identifier}. Eigene Items (Werkzeuge, Materialien) erben hiervon.
 */
public class Item {

    public static final int DEFAULT_MAX_STACK = 64;

    private final Identifier id;
    private final int maxStackSize;

    public Item(Identifier id) {
        this(id, DEFAULT_MAX_STACK);
    }

    public Item(Identifier id, int maxStackSize) {
        this.id = id;
        this.maxStackSize = maxStackSize;
    }

    public Identifier getId() {
        return id;
    }

    public int getMaxStackSize() {
        return maxStackSize;
    }

    @Override
    public String toString() {
        return id.toString();
    }
}
