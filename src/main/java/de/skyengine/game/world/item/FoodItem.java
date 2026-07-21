package de.skyengine.game.world.item;

import de.skyengine.game.world.block.Identifier;

/**
 * Essbares Item (MC-Werte): {@code nutrition} füllt den Hungerbalken (in Halb-Icons),
 * {@code saturation} die unsichtbare Sättigung. Die Ess-Logik (Rechtsklick halten, tick-basiert)
 * steckt im {@code GameContainer}; die Wirkung in {@code EntityPlayer.eat}.
 */
public final class FoodItem extends Item {

    private final int nutrition;
    private final float saturation;
    private final String iconTexture;

    public FoodItem(Identifier id, int nutrition, float saturation, String iconTexture) {
        super(id);
        this.nutrition = nutrition;
        this.saturation = saturation;
        this.iconTexture = iconTexture;
    }

    public int getNutrition() {
        return nutrition;
    }

    public float getSaturation() {
        return saturation;
    }

    @Override
    public String getIconTexture() {
        return iconTexture;
    }
}
