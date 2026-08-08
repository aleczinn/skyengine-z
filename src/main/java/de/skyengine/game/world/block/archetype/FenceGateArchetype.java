package de.skyengine.game.world.block.archetype;

import de.skyengine.game.world.block.Direction;
import de.skyengine.game.world.block.behavior.FenceGateBehavior;
import de.skyengine.game.world.block.json.BlockDefinition;
import de.skyengine.game.world.block.shape.Shapes;
import de.skyengine.game.world.block.state.Properties;

/** Hölzernes Zauntor mit Blickrichtung, Offen-/Power-Zustand und Mauerabsenkung. */
public final class FenceGateArchetype implements Archetype {

    @Override
    public void configure(BlockConfig.Builder cfg, BlockDefinition def) {
        cfg.property(Properties.FACING)
                .property(Properties.OPEN)
                .property(Properties.POWERED)
                .property(Properties.IN_WALL)
                .defaultValue(Properties.FACING, Direction.NORTH)
                .behavior(new FenceGateBehavior())
                .shapes(Shapes.fenceGate())
                .opaque(state -> false)
                .redstoneConductor(state -> false)
                .connectionGroup("fence_gate");
    }
}
