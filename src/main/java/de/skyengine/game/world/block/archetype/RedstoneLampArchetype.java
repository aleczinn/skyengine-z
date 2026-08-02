package de.skyengine.game.world.block.archetype;

import de.skyengine.game.world.block.behavior.LampBehavior;
import de.skyengine.game.world.block.json.BlockDefinition;
import de.skyengine.game.world.block.state.Properties;

/**
 * Redstone-Lampe: voller Würfel + LIT-Property (2 States). Leuchtstärke kommt aus
 * {@code light_level} der Block-JSON und wirkt nur bei lit=true (LIT-Konvention in
 * {@code Block.getLuminance}).
 */
public final class RedstoneLampArchetype implements Archetype {

    @Override
    public void configure(BlockConfig.Builder cfg, BlockDefinition def) {
        cfg.property(Properties.LIT)
                .behavior(new LampBehavior());
    }
}
