package de.skyengine.game.world.block.archetype;

import de.skyengine.game.world.block.Direction;
import de.skyengine.game.world.block.json.BlockDefinition;
import de.skyengine.game.world.block.shape.BlockShape;
import de.skyengine.game.world.block.shape.ShapeProvider;
import de.skyengine.game.world.block.state.BlockState;
import de.skyengine.game.world.block.state.Properties;

/**
 * Bewegter Block (MCs Block 36, technisch, {@code no_item}). Nur eine einfahrende Source-BE
 * besitzt eine statische Render-Hülle für die ausgefahrene Basis; alle bewegten Teile rendert
 * der {@code PistonMovingRenderer}. Die Hülle verhindert, dass dieselbe Basis beim asynchronen
 * Chunk-Remesh gleichzeitig als alter Piston und flach beleuchtete BE-Geometrie erscheint.
 * Kollision und bewegte Geometrie kommen dynamisch aus der BlockEntity; die technische Zelle
 * selbst hat wie Vanilla keine eigenständige Outline und bleibt unanvisierbar.
 */
public final class MovingPistonArchetype implements Archetype {

    @Override
    public void configure(BlockConfig.Builder cfg, BlockDefinition def) {
        cfg.property(Properties.FACING_ALL)
                .property(Properties.PISTON_TYPE)
                .property(Properties.RETRACTING_SOURCE)
                .defaultValue(Properties.FACING_ALL, Direction.NORTH)
                .behavior(new de.skyengine.game.world.block.behavior.MovingPistonBehavior())
                .shapes(new ShapeProvider() {
                    @Override public BlockShape collision(BlockState state) { return BlockShape.EMPTY; }
                    @Override public BlockShape outline(BlockState state) { return BlockShape.EMPTY; }
                })
                .opaque(state -> false)
                /* Gleiche AO-Wirkung wie die ausgefahrene echte Basis; Fracht-/Extend-States
                   bleiben als technische leere Zellen bewusst Nicht-Okkludierer. */
                .aoOccluder(state -> state.get(Properties.RETRACTING_SOURCE));
    }
}
