package de.skyengine.game.world.block.shape;

import de.skyengine.game.physics.AABB;
import de.skyengine.game.world.block.Direction;
import de.skyengine.game.world.block.model.BlockStateModels;
import de.skyengine.game.world.block.state.AttachFace;
import de.skyengine.game.world.block.state.BlockState;
import de.skyengine.game.world.block.state.DoorHinge;
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

    /* Fackel-Umrisse in Pixeln/16 (Vanilla): Boden mittig, Wand an der Trägerseite. */
    private static final BlockShape TORCH_FLOOR = px(6, 0, 6, 10, 10, 10);
    private static final BlockShape TORCH_WALL_NORTH = px(5.5, 3, 11, 10.5, 13, 16);
    private static final BlockShape TORCH_WALL_SOUTH = px(5.5, 3, 0, 10.5, 13, 5);
    private static final BlockShape TORCH_WALL_WEST = px(11, 3, 5.5, 16, 13, 10.5);
    private static final BlockShape TORCH_WALL_EAST = px(0, 3, 5.5, 5, 13, 10.5);

    private static BlockShape px(double x0, double y0, double z0, double x1, double y1, double z1) {
        return BlockShape.box(x0 / 16.0, y0 / 16.0, z0 / 16.0, x1 / 16.0, y1 / 16.0, z1 / 16.0);
    }

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

    /**
     * Tür: 3px-Panel als Riegel. Geschlossen blockiert es die Durchgangsachse (FACING-Kante),
     * offen liegt es an der Seitenkante (durchgehbar). Komplett zustandsabhängig, modellunabhängig.
     */
    public static ShapeProvider door() {
        return state -> new BlockShape(new AABB[]{ slab(panelDir(state)) });
    }

    private static Direction panelDir(BlockState state) {
        Direction facing = state.get(Properties.FACING);
        if (!state.get(Properties.OPEN)) return facing;
        /* Offen: schwingt zur Hinge-Seite (LEFT -> CW, RIGHT -> CCW), passend zur Modell-Rotation. */
        return state.get(Properties.HINGE) == DoorHinge.LEFT ? facing.rotateYCW() : facing.rotateYCCW();
    }

    private static AABB slab(Direction edge) {
        double t = 3.0 / 16.0;
        return switch (edge) {
            case NORTH -> new AABB(0, 0, 0, 1, 1, t);
            case SOUTH -> new AABB(0, 0, 1 - t, 1, 1, 1);
            case WEST -> new AABB(0, 0, 0, t, 1, 1);
            case EAST -> new AABB(1 - t, 0, 0, 1, 1, 1);
            default -> new AABB(0, 0, 0, 1, 1, t);
        };
    }

    /**
     * Attached (Fackel): keine Kollision, Umriss je nach Trägerfläche. Boxen verbatim aus Vanillas
     * {@code TorchBlock}/{@code WallTorchBlock} — bei WALL steht die Fackel an der Trägerseite,
     * also der FACING-Richtung gegenüber (FACING zeigt vom Träger weg).
     */
    public static ShapeProvider attached() {
        return new ShapeProvider() {
            @Override public BlockShape collision(BlockState state) { return BlockShape.EMPTY; }
            @Override public BlockShape outline(BlockState state) {
                if (state.get(Properties.ATTACH) != AttachFace.WALL) return TORCH_FLOOR;
                return switch (state.get(Properties.FACING)) {
                    case NORTH -> TORCH_WALL_NORTH;
                    case SOUTH -> TORCH_WALL_SOUTH;
                    case WEST -> TORCH_WALL_WEST;
                    default -> TORCH_WALL_EAST;
                };
            }
        };
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
