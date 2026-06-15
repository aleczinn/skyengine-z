package de.skyengine.game.world.block.json;

import de.skyengine.game.world.block.Block;
import de.skyengine.game.world.block.Identifier;

/**
 * Zaun: 4px-Pfosten + Arme (Optik via Blockstate), Kollision 1.5 hoch.
 */
public class FenceBlock extends ConnectingBlock {

    public FenceBlock(Identifier identifier, Settings settings, BlockDefinition definition) {
        super(identifier, settings, definition);
    }

    @Override
    protected double collisionHeight() {
        return 1.5;
    }

    @Override
    protected boolean connectsToFamily(Block other) {
        return other instanceof FenceBlock;
    }
}
