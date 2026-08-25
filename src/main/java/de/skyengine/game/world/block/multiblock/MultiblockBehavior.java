package de.skyengine.game.world.block.multiblock;

import de.skyengine.game.world.Dimension;
import de.skyengine.game.world.block.behavior.BlockBehavior;
import de.skyengine.game.world.block.state.BlockState;
import de.skyengine.game.world.block.state.Property;

/**
 * Setzt eine boolean „formed"-Property eines Controller-Blocks anhand eines
 * {@link MultiblockPattern} bei Nachbaränderungen. Parts bleiben normale Blöcke ohne eigene
 * Logik — nur der Controller trägt Verhalten/BlockEntity. Skaliert für große Maschinen.
 */
public final class MultiblockBehavior implements BlockBehavior {

    private final MultiblockPattern pattern;
    private final Property<Boolean> formed;

    public MultiblockBehavior(MultiblockPattern pattern, Property<Boolean> formed) {
        this.pattern = pattern;
        this.formed = formed;
    }

    @Override
    public BlockState onNeighborUpdate(Dimension world, int x, int y, int z, BlockState state) {
        return state.with(this.formed, this.pattern.matches(world, x, y, z));
    }
}
