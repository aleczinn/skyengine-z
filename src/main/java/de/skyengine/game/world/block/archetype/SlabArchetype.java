package de.skyengine.game.world.block.archetype;

import de.skyengine.game.world.block.behavior.SlabPlacementBehavior;
import de.skyengine.game.world.block.json.BlockDefinition;
import de.skyengine.game.world.block.shape.Shapes;
import de.skyengine.game.world.block.state.Properties;
import de.skyengine.game.world.block.state.SlabType;

/** Slab: SLAB_TYPE-Property, prozedurale Shape, Doppel-Slab ist opak. */
public final class SlabArchetype implements Archetype {

    @Override
    public void configure(BlockConfig.Builder cfg, BlockDefinition def) {
        cfg.property(Properties.SLAB_TYPE)
                .behavior(new SlabPlacementBehavior())
                .shapes(Shapes.slab())
                .opaque(state -> state.get(Properties.SLAB_TYPE) == SlabType.DOUBLE);
    }
}
