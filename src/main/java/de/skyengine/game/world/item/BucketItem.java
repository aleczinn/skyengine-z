package de.skyengine.game.world.item;

import de.skyengine.game.world.block.Block;
import de.skyengine.game.world.block.Identifier;

/**
 * Eimer-Item: leer ({@code fluid == null}) oder mit einem Fluid gefüllt. Ein gefüllter Eimer
 * platziert per Rechtsklick eine Fluid-Quelle; ein leerer Eimer nimmt eine Quelle auf. Die
 * Verbrauchs-/Tausch-Logik (nur im Survival) steckt im {@code GameContainer}.
 */
public final class BucketItem extends Item {

    private final Block fluid; // null = leerer Eimer
    private final String iconTexture;

    public BucketItem(Identifier id, Block fluid, String iconTexture, int maxStackSize) {
        super(id, maxStackSize);
        this.fluid = fluid;
        this.iconTexture = iconTexture;
    }

    /** Fluid-Block dieses Eimers, oder {@code null} wenn leer. */
    public Block getFluid() {
        return fluid;
    }

    public boolean isEmpty() {
        return fluid == null;
    }

    @Override
    public String getIconTexture() {
        return iconTexture;
    }
}
