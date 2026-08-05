package de.skyengine.game.world.block.archetype;

import de.skyengine.game.world.block.behavior.ComparatorBehavior;
import de.skyengine.game.world.block.json.BlockDefinition;
import de.skyengine.game.world.block.shape.BlockShape;
import de.skyengine.game.world.block.shape.ShapeProvider;
import de.skyengine.game.world.block.state.BlockState;
import de.skyengine.game.world.block.state.Properties;

/**
 * Komparator: FACING (4, = Ausgangsrichtung) × MODE (compare/subtract) × POWER (0..15)
 * = 128 States — der Ausgangswert liegt im State, die Palette persistiert ihn gratis.
 * Flache 2-px-Platte wie der Verstärker; Träger über {@code place_on_full_top}.
 */
public final class ComparatorArchetype implements Archetype {

    private static final BlockShape PLATE = BlockShape.box(0, 0, 0, 1, 2 / 16.0, 1);

    @Override
    public void configure(BlockConfig.Builder cfg, BlockDefinition def) {
        cfg.property(Properties.FACING)
                .property(Properties.MODE)
                .property(Properties.POWER)
                .behavior(new ComparatorBehavior())
                .shapes(new ShapeProvider() {
                    @Override public BlockShape collision(BlockState state) { return PLATE; }
                    @Override public BlockShape outline(BlockState state) { return PLATE; }
                })
                .opaque(state -> false);
    }
}
