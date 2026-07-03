package de.skyengine.game.world.block.archetype;

import de.skyengine.game.world.block.behavior.PillarPlacementBehavior;
import de.skyengine.game.world.block.json.BlockDefinition;
import de.skyengine.game.world.block.state.Properties;

/** Säule/Stamm: AXIS-Property aus der Platzierungsfläche; voller Würfel (Optik via Blockstate). */
public final class PillarArchetype implements Archetype {

    @Override
    public void configure(BlockConfig.Builder cfg, BlockDefinition def) {
        cfg.property(Properties.AXIS)
                .behavior(new PillarPlacementBehavior());
    }
}
