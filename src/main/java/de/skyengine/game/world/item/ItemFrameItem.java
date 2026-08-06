package de.skyengine.game.world.item;

import de.skyengine.game.world.block.Identifier;

/** Das platzierbare Item der gleichnamigen Hanging-Entity. */
public final class ItemFrameItem extends Item {

    private final String texture;

    public ItemFrameItem(Identifier id, String texture) {
        super(id);
        this.texture = texture;
    }

    @Override
    public String getIconTexture() {
        return this.texture;
    }
}
