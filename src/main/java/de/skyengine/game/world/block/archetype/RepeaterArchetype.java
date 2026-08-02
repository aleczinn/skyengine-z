package de.skyengine.game.world.block.archetype;

import de.skyengine.game.world.block.behavior.RepeaterBehavior;
import de.skyengine.game.world.block.json.BlockDefinition;
import de.skyengine.game.world.block.shape.BlockShape;
import de.skyengine.game.world.block.shape.ShapeProvider;
import de.skyengine.game.world.block.state.BlockState;
import de.skyengine.game.world.block.state.Properties;

/**
 * Verstärker: FACING (= Ausgangsrichtung) × DELAY 1-4 × POWERED = 32 States. Flache
 * 2-px-Platte als Kollision UND Umriss (Vanilla); Träger über {@code place_on_full_top}.
 */
public final class RepeaterArchetype implements Archetype {

    private static final BlockShape PLATE = BlockShape.box(0, 0, 0, 1, 2 / 16.0, 1);

    @Override
    public void configure(BlockConfig.Builder cfg, BlockDefinition def) {
        cfg.property(Properties.FACING)
                .property(Properties.DELAY)
                .property(Properties.POWERED)
                /* LOCKED: 32 -> 64 States; alte Saves laden über den Codec-Default locked=false. */
                .property(Properties.LOCKED)
                .behavior(new RepeaterBehavior())
                .shapes(new ShapeProvider() {
                    @Override public BlockShape collision(BlockState state) { return PLATE; }
                    @Override public BlockShape outline(BlockState state) { return PLATE; }
                })
                .opaque(state -> false);
    }
}
