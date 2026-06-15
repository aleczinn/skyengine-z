package de.skyengine.game.world.block.shape;

import de.skyengine.game.physics.AABB;
import de.skyengine.game.world.block.model.BlockStateModels;
import de.skyengine.game.world.block.state.BlockState;
import de.skyengine.game.world.block.state.Properties;

import java.util.ArrayList;
import java.util.List;

/**
 * Wiederverwendbare {@link ShapeProvider}-Fabriken für die Archetypen. Diese Formen
 * sind unabhängig vom Modell definiert und durch JSON-Overrides ersetzbar.
 */
public final class Shapes {

    private static final BlockShape SLAB_BOTTOM = BlockShape.box(0, 0, 0, 1, 0.5, 1);
    private static final BlockShape SLAB_TOP = BlockShape.box(0, 0.5, 0, 1, 1, 1);
    private static final BlockShape CROSS_OUTLINE = BlockShape.box(0.1, 0.0, 0.1, 0.9, 0.8, 0.9);

    /** Slab: untere/obere Hälfte oder voller Würfel — prozedural, modellunabhängig. */
    public static ShapeProvider slab() {
        return state -> switch (state.get(Properties.SLAB_TYPE)) {
            case BOTTOM -> SLAB_BOTTOM;
            case TOP -> SLAB_TOP;
            case DOUBLE -> BlockShape.FULL_CUBE;
        };
    }

    /**
     * Leitet die Form aus den gebackenen Modell-Boxen ab. Übergangslösung für komplexe
     * Formen (Treppe) mit exakter Parität zu Phase 3; per JSON-Override ablösbar.
     */
    public static ShapeProvider modelDerived() {
        return state -> new BlockShape(BlockStateModels.bake(state.getBlock(), state).boxes());
    }

    /**
     * Verbindungs-Shape (Zaun/Pane): höchstens zwei sich am Pfosten überschneidende Balken
     * (Z = NORTH..SOUTH, X = WEST..EAST) in Pfostenbreite [a,b]. Kollisionshöhe ist ein
     * Gameplay-Wert (Zaun 1.5), <b>unabhängig</b> vom Modell; Umriss nutzt Höhe 1.0.
     */
    public static ShapeProvider connected(double postMin, double postMax, double collisionHeight) {
        return new ShapeProvider() {
            @Override public BlockShape collision(BlockState state) {
                return new BlockShape(bars(state, postMin, postMax, collisionHeight));
            }
            @Override public BlockShape outline(BlockState state) {
                return new BlockShape(bars(state, postMin, postMax, 1.0));
            }
        };
    }

    private static AABB[] bars(BlockState state, double a, double b, double height) {
        boolean n = state.get(Properties.NORTH), s = state.get(Properties.SOUTH);
        boolean w = state.get(Properties.WEST), e = state.get(Properties.EAST);

        List<AABB> boxes = new ArrayList<>(2);
        if (n || s) boxes.add(new AABB(a, 0, n ? 0 : a, b, height, s ? 1 : b));
        if (w || e) boxes.add(new AABB(w ? 0 : a, 0, a, e ? 1 : b, height, b));
        if (boxes.isEmpty()) boxes.add(new AABB(a, 0, a, b, height, b));
        return boxes.toArray(new AABB[0]);
    }

    /** Cross (Gras/Blumen): keine Kollision, kleine Umriss-Box. */
    public static ShapeProvider cross() {
        return new ShapeProvider() {
            @Override public BlockShape collision(BlockState state) { return BlockShape.EMPTY; }
            @Override public BlockShape outline(BlockState state) { return CROSS_OUTLINE; }
        };
    }

    private Shapes() {}
}
