package de.skyengine.game.world.block.json;

import de.skyengine.game.world.block.Block;
import de.skyengine.game.world.block.Identifier;

/** Dünne Scheibe (Glass-Pane, Iron-Bars): 2px-Pfosten + Arme (Optik via Blockstate). */
public class PaneBlock extends ConnectingBlock {

    public PaneBlock(Identifier identifier, Settings settings, BlockDefinition definition) {
        super(identifier, settings, definition);
    }

    @Override protected double collisionHeight() { return 1.0; }

    @Override
    protected boolean connectsToFamily(Block other) {
        return other instanceof PaneBlock;
    }
}
