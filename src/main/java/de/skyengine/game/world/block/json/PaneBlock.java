package de.skyengine.game.world.block.json;

import de.skyengine.game.world.block.Block;
import de.skyengine.game.world.block.Identifier;

/**
 * Dünne Scheibe (Glass-Pane, Iron-Bars): 2px-Pfosten + dünne Arme, verbindet
 * an andere Panes/Bars und volle Blöcke. Kollision 1.0 hoch.
 */
public class PaneBlock extends ConnectingBlock {

    public PaneBlock(Identifier identifier, Settings settings, BlockDefinition definition) {
        super(identifier, settings, definition);
    }

    @Override protected double postMin() { return 0.4375; }
    @Override protected double postMax() { return 0.5625; }
    @Override protected double collisionHeight() { return 1.0; }

    /** Ein durchgehender Riegel (Glasscheibe/Gitter ist voll). */
    @Override
    protected double[][] armSegments() {
        return new double[][]{{0.0, 1.0}};
    }

    @Override
    protected boolean connectsToFamily(Block other) {
        return other instanceof PaneBlock;
    }
}
