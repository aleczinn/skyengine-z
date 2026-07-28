package de.skyengine.game.world.block.archetype;

import de.skyengine.game.world.block.behavior.ChestBehavior;
import de.skyengine.game.world.block.json.BlockDefinition;
import de.skyengine.game.world.block.state.Properties;

/**
 * Truhe: FACING + CHEST_TYPE (single/left/right) und das {@link ChestBehavior}, das Ausrichtung,
 * Verschmelzen zur Doppeltruhe und das Auftrennen übernimmt.
 *
 * <p>Das FACING kommt bewusst von hier und nicht über das JSON-Flag {@code facing}: dessen
 * {@code HorizontalFacingBehavior} würde die Ausrichtung ein zweites Mal setzen und dabei das
 * vom Partner übernommene Facing wieder überschreiben.
 */
public final class ChestArchetype implements Archetype {

    @Override
    public void configure(BlockConfig.Builder cfg, BlockDefinition def) {
        cfg.property(Properties.FACING)
                .property(Properties.CHEST_TYPE)
                .behavior(new ChestBehavior());
    }
}
