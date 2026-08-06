package de.skyengine.game.world.block.archetype;

import de.skyengine.game.world.block.json.BlockDefinition;
import de.skyengine.game.world.block.shape.BlockShape;
import de.skyengine.game.world.block.shape.ShapeProvider;
import de.skyengine.game.world.block.state.BlockState;

/**
 * Bewegter Block (MCs Block 36, technisch, {@code no_item}): 1 State, kein statisches
 * Modell (leere Geometrie — gerendert wird die BlockEntity über den PistonMovingRenderer).
 * Kollision und Rendering kommen dynamisch aus der BlockEntity; die technische Zelle selbst
 * hat wie Vanilla keine eigenständige statische Form und bleibt unanvisierbar.
 */
public final class MovingPistonArchetype implements Archetype {

    @Override
    public void configure(BlockConfig.Builder cfg, BlockDefinition def) {
        cfg.behavior(new de.skyengine.game.world.block.behavior.MovingPistonBehavior())
                .shapes(new ShapeProvider() {
                    @Override public BlockShape collision(BlockState state) { return BlockShape.EMPTY; }
                    @Override public BlockShape outline(BlockState state) { return BlockShape.EMPTY; }
                })
                .opaque(state -> false);
    }
}
