package de.skyengine.game.world.item;

import de.skyengine.game.world.block.Identifier;

/** Schere als Loot-relevantes, nicht stapelbares Werkzeug. */
public final class ShearsItem extends Item {

    public static final int DURABILITY = 238;
    private final String iconTexture;

    public ShearsItem(Identifier id, String iconTexture) {
        super(id, 1);
        this.iconTexture = iconTexture;
    }

    @Override
    public String getIconTexture() {
        return this.iconTexture;
    }
}
