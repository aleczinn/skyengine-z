package de.skyengine.game.world.block.json;

import de.skyengine.game.world.block.Block;
import de.skyengine.game.world.block.Identifier;

/** Zaun: 4px-Pfosten + Arme, verbindet an Zäune und volle Blöcke. Kollision 1.5 hoch. */
public class FenceBlock extends ConnectingBlock {

    public FenceBlock(Identifier identifier, Settings settings, BlockDefinition definition) {
        super(identifier, settings, definition);
    }

    @Override protected double postMin() { return 0.375; }
    @Override protected double postMax() { return 0.625; }
    @Override protected double collisionHeight() { return 1.5; }

    /** Zwei Riegel mit Hohlraum dazwischen (wie Minecraft). */
    @Override
    protected double[][] armSegments() {
        return new double[][]{{0.375, 0.5625}, {0.75, 0.9375}};
    }

    @Override
    protected boolean connectsToFamily(Block other) {
        return other instanceof FenceBlock;
    }
}
