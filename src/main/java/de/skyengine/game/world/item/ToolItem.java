package de.skyengine.game.world.item;

import de.skyengine.game.world.block.Identifier;

/**
 * Werkzeug (Spitzhacke, Axt, Schaufel, Schwert): Klasse + Material bestimmen
 * Abbau-Geschwindigkeit, Harvest-Level und Haltbarkeit (siehe GameContainer-Mining-Loop).
 * Nicht stapelbar; Icon ist ein flaches Item-Sprite (Bucket-Muster).
 */
public final class ToolItem extends Item {

    private final ToolType type;
    private final ToolTier tier;
    private final String iconTexture;

    public ToolItem(Identifier id, ToolType type, ToolTier tier, String iconTexture) {
        super(id, 1);
        this.type = type;
        this.tier = tier;
        this.iconTexture = iconTexture;
    }

    public ToolType getType() {
        return type;
    }

    public ToolTier getTier() {
        return tier;
    }

    @Override
    public String getIconTexture() {
        return iconTexture;
    }
}
