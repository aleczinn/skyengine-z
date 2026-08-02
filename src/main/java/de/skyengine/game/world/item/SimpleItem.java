package de.skyengine.game.world.item;

import de.skyengine.game.world.block.Block;
import de.skyengine.game.world.block.Identifier;

/**
 * Einfaches Material-Item ohne Sonderverhalten (Barren, Stock, Kohle, Edelstein). Wird aus
 * {@code game/items/*.json} erzeugt; das Rendering läuft über {@link #getIconTexture()} —
 * flaches Sprite im Inventar, extrudiert in der Hand (wie bei Eimern und Essen).
 * Optional platzierbar: {@code places_block} in der JSON macht das Item zum Platzierer
 * eines fremden Blocks (Redstone-Staub), der Block selbst hat dann {@code no_item}.
 */
public final class SimpleItem extends Item {

    private final String iconTexture;
    private final Block placedBlock;

    public SimpleItem(Identifier id, int maxStackSize, String iconTexture) {
        this(id, maxStackSize, iconTexture, null);
    }

    public SimpleItem(Identifier id, int maxStackSize, String iconTexture, Block placedBlock) {
        super(id, maxStackSize);
        this.iconTexture = iconTexture;
        this.placedBlock = placedBlock;
    }

    @Override
    public String getIconTexture() {
        return iconTexture;
    }

    @Override
    public Block getPlacedBlock() {
        return placedBlock;
    }
}
