package de.skyengine.game.world.item;

import de.skyengine.game.world.block.Identifier;

/** Platzierbares Standard-Minecart; die Schienenprüfung erfolgt im Interaktionspfad. */
public final class MinecartItem extends Item {

    private final String texture;

    public MinecartItem(Identifier id, String texture) {
        super(id, 1);
        this.texture = texture;
    }

    @Override
    public String getIconTexture() {
        return this.texture;
    }
}
