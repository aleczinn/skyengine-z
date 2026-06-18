package de.skyengine.game.world.block.shape;

import de.skyengine.game.world.block.state.BlockState;

/**
 * Liefert Kollisions- und Umriss-Form eines States. Bewusst <b>getrennt</b> vom
 * Modell-System: Gameplay-Maße (z.B. Zaun-Kollision 1.5 hoch) dürfen von der Optik
 * abweichen. Umriss fällt per Default auf die Kollisionsform zurück.
 */
public interface ShapeProvider {

    BlockShape collision(BlockState state);

    default BlockShape outline(BlockState state) {
        return collision(state);
    }
}
