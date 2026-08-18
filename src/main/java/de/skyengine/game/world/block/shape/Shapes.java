package de.skyengine.game.world.block.shape;

import de.skyengine.game.physics.AABB;
import de.skyengine.game.world.block.Direction;
import de.skyengine.game.world.block.model.BlockStateModels;
import de.skyengine.game.world.block.state.AttachFace;
import de.skyengine.game.world.block.state.BlockHalf;
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

    private static volatile java.util.Map<Integer, BlockShape> defaultModelShapes = java.util.Map.of();
    private static final java.util.Map<Integer, BlockShape> capturedModelShapes = new java.util.HashMap<>();
    private static boolean captureModelShapes = true;

    private static final BlockShape SLAB_BOTTOM = BlockShape.box(0, 0, 0, 1, 0.5, 1);
    private static final BlockShape SLAB_TOP = BlockShape.box(0, 0.5, 0, 1, 1, 1);
    private static final BlockShape CROSS_OUTLINE = BlockShape.box(0.1, 0.0, 0.1, 0.9, 0.8, 0.9);

    /* Fackel-Umrisse in Pixeln/16 (Vanilla): Boden mittig, Wand an der Trägerseite. */
    private static final BlockShape TORCH_FLOOR = px(6, 0, 6, 10, 10, 10);
    private static final BlockShape TORCH_WALL_NORTH = px(5.5, 3, 11, 10.5, 13, 16);
    private static final BlockShape TORCH_WALL_SOUTH = px(5.5, 3, 0, 10.5, 13, 5);
    private static final BlockShape TORCH_WALL_WEST = px(11, 3, 5.5, 16, 13, 10.5);
    private static final BlockShape TORCH_WALL_EAST = px(0, 3, 5.5, 5, 13, 10.5);

    /* Falltür geschlossen: 3px-Platte unten bzw. oben (Vanilla-Maße). Offen ist sie ein
       senkrechtes 3px-Panel und benutzt dieselbe slab(Direction)-Hilfe wie die Tür. */
    private static final BlockShape TRAPDOOR_BOTTOM = px(0, 0, 0, 16, 3, 16);
    private static final BlockShape TRAPDOOR_TOP = px(0, 13, 0, 16, 16, 16);

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
        return state -> {
            BlockShape frozen = defaultModelShapes.get(state.getId());
            if (frozen != null) return frozen;
            if (captureModelShapes) {
                BlockShape captured = capturedModelShapes.get(state.getId());
                if (captured != null) return captured;
            }
            BlockShape derived = new BlockShape(BlockStateModels.bake(state.getBlock(), state).boxes());
            if (captureModelShapes) capturedModelShapes.putIfAbsent(state.getId(), derived);
            return derived;
        };
    }

    /** Friert modellabgeleitete Gameplay-Formen ein, bevor visuelle Packs geladen werden. */
    public static void freezeDefaultModelShapes() {
        for (int id = 0; id < de.skyengine.game.world.block.BlockRegistry.getStateCount(); id++) {
            var state = de.skyengine.game.world.block.BlockRegistry.getState(id);
            /* Nur Provider, die modelDerived() wirklich verwenden, tragen sich dabei ein.
               So werden Fluids/Sondermodelle nicht grundlos gebacken und outlineOnly wird
               korrekt ueber seinen Outline-Aufruf erfasst. */
            state.getBlock().getCollisionShape(state);
            state.getBlock().getOutlineShape(state);
        }
        defaultModelShapes = java.util.Map.copyOf(capturedModelShapes);
        capturedModelShapes.clear();
        captureModelShapes = false;
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
     * Falltür: geschlossen eine waagerechte 3px-Platte (unten oder oben je HALF), offen ein
     * senkrechtes 3px-Panel an der FACING-GEGENÜBERLIEGENDEN Kante — sie hängt am Block, an den
     * sie gesetzt wurde, und FACING zeigt von dort weg. Das deckt sich mit Vanillas
     * {@code TrapDoorBlock} (facing=north -> Panel bei z 13..16) und mit der Modell-Rotation
     * der Blockstate-Varianten.
     */
    public static ShapeProvider trapdoor() {
        return state -> {
            if (state.get(Properties.OPEN)) {
                return new BlockShape(new AABB[]{ slab(state.get(Properties.FACING).opposite()) });
            }
            return state.get(Properties.HALF) == BlockHalf.BOTTOM ? TRAPDOOR_BOTTOM : TRAPDOOR_TOP;
        };
    }

    /** Zauntor: offen ohne Kollision, geschlossen 1,5 Blöcke hoch; Umriss bleibt blockhoch. */
    public static ShapeProvider fenceGate() {
        return new ShapeProvider() {
            @Override
            public BlockShape collision(BlockState state) {
                if (state.get(Properties.OPEN)) return BlockShape.EMPTY;
                return state.get(Properties.FACING).axis() == Direction.Axis.Z
                        ? px16(0, 0, 5, 16, 24, 11)
                        : px16(5, 0, 0, 11, 24, 16);
            }

            @Override
            public BlockShape outline(BlockState state) {
                double top = state.get(Properties.IN_WALL) ? 13 : 16;
                return state.get(Properties.FACING).axis() == Direction.Axis.Z
                        ? px16(0, 0, 5, 16, top, 11)
                        : px16(5, 0, 0, 11, top, 16);
            }
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

    /* Hebel-Umrisse verbatim aus MCs LeverBlock (px/16): enge Box um den SOCKEL, der
       gekippte Griff bleibt draußen — die modellabgeleitete Box wäre durch ihn aufgebläht.
       Bei WALL gilt dieselbe Konvention wie bei den TORCH_WALL_*-Boxen oben: FACING zeigt
       vom Träger weg, die Box klebt an der Trägerseite. */
    private static final BlockShape LEVER_FLOOR_Z = px(5, 0, 4, 11, 10, 12);
    private static final BlockShape LEVER_FLOOR_X = px(4, 0, 5, 12, 10, 11);
    private static final BlockShape LEVER_CEILING_Z = px(5, 6, 4, 11, 16, 12);
    private static final BlockShape LEVER_CEILING_X = px(4, 6, 5, 12, 16, 11);
    private static final BlockShape LEVER_WALL_NORTH = px(5, 4, 10, 11, 12, 16);
    private static final BlockShape LEVER_WALL_SOUTH = px(5, 4, 0, 11, 12, 6);
    private static final BlockShape LEVER_WALL_WEST = px(10, 4, 5, 16, 12, 11);
    private static final BlockShape LEVER_WALL_EAST = px(0, 4, 5, 6, 12, 11);

    /** Hebel: keine Kollision, feste Sockel-Box je Trägerfläche und Ausrichtung (Vanilla). */
    public static ShapeProvider lever() {
        return new ShapeProvider() {
            @Override public BlockShape collision(BlockState state) { return BlockShape.EMPTY; }
            @Override public BlockShape outline(BlockState state) {
                Direction facing = state.get(Properties.FACING);
                boolean zAxis = facing == Direction.NORTH || facing == Direction.SOUTH;
                return switch (state.get(Properties.ATTACH)) {
                    case FLOOR -> zAxis ? LEVER_FLOOR_Z : LEVER_FLOOR_X;
                    case CEILING -> zAxis ? LEVER_CEILING_Z : LEVER_CEILING_X;
                    case WALL -> switch (facing) {
                        case NORTH -> LEVER_WALL_NORTH;
                        case SOUTH -> LEVER_WALL_SOUTH;
                        case WEST -> LEVER_WALL_WEST;
                        default -> LEVER_WALL_EAST;
                    };
                };
            }
        };
    }

    /**
     * Keine Kollision, Umriss aus den Modell-Boxen (Knopf, Druckplatte, Staub): man läuft
     * durch den Block hindurch, kann ihn aber anvisieren — und der Umriss folgt automatisch
     * dem zustandsabhängigen Modell (gedrückt/ungedrückt).
     */
    public static ShapeProvider outlineOnly() {
        ShapeProvider derived = modelDerived();
        return new ShapeProvider() {
            @Override public BlockShape collision(BlockState state) { return BlockShape.EMPTY; }
            @Override public BlockShape outline(BlockState state) { return derived.outline(state); }
        };
    }

    /**
     * Kolben-Basis: eingefahren ein voller Würfel, ausgefahren fehlen die 4 px an der
     * FACING-Seite (dort gleitet der Arm heraus). Kollision = Umriss.
     */
    public static ShapeProvider piston() {
        return state -> {
            if (!state.get(Properties.EXTENDED)) return BlockShape.FULL_CUBE;
            return switch (state.get(Properties.FACING_ALL)) {
                case NORTH -> px16(0, 0, 4, 16, 16, 16);
                case SOUTH -> px16(0, 0, 0, 16, 16, 12);
                case WEST -> px16(4, 0, 0, 16, 16, 16);
                case EAST -> px16(0, 0, 0, 12, 16, 16);
                case UP -> px16(0, 0, 0, 16, 12, 16);
                case DOWN -> px16(0, 4, 0, 16, 16, 16);
            };
        };
    }

    /**
     * Kolben-Kopf: 4-px-Platte an der FACING-Seite + 4×4-Arm-Steg dahinter (der 4-px-Überstand
     * des Modells in die Basis-Zelle bleibt außen vor — Kollision endet an der Blockgrenze).
     */
    public static ShapeProvider pistonHead() {
        return state -> switch (state.get(Properties.FACING_ALL)) {
            case NORTH -> boxes(pxBox(0, 0, 0, 16, 16, 4), pxBox(6, 6, 4, 10, 10, 16));
            case SOUTH -> boxes(pxBox(0, 0, 12, 16, 16, 16), pxBox(6, 6, 0, 10, 10, 12));
            case WEST -> boxes(pxBox(0, 0, 0, 4, 16, 16), pxBox(4, 6, 6, 16, 10, 10));
            case EAST -> boxes(pxBox(12, 0, 0, 16, 16, 16), pxBox(0, 6, 6, 12, 10, 10));
            case UP -> boxes(pxBox(0, 12, 0, 16, 16, 16), pxBox(6, 0, 6, 10, 12, 10));
            case DOWN -> boxes(pxBox(0, 0, 0, 16, 4, 16), pxBox(6, 4, 6, 10, 16, 10));
        };
    }

    private static BlockShape px16(double x0, double y0, double z0, double x1, double y1, double z1) {
        return BlockShape.box(x0 / 16, y0 / 16, z0 / 16, x1 / 16, y1 / 16, z1 / 16);
    }

    private static AABB pxBox(double x0, double y0, double z0, double x1, double y1, double z1) {
        return new AABB(x0 / 16, y0 / 16, z0 / 16, x1 / 16, y1 / 16, z1 / 16);
    }

    private static BlockShape boxes(AABB... parts) {
        return new BlockShape(parts);
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
