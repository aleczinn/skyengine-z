package de.skyengine.game.world.block;

/**
 * Die sechs Achsenrichtungen. Die Face-Indizes entsprechen exakt der Mesher-/
 * BlockModels-Konvention: 0=top, 1=bottom, 2=north(-z), 3=south(+z),
 * 4=west(-x), 5=east(+x).
 */
public enum Direction {

    UP(0, 0, 1, 0, Axis.Y),
    DOWN(1, 0, -1, 0, Axis.Y),
    NORTH(2, 0, 0, -1, Axis.Z),
    SOUTH(3, 0, 0, 1, Axis.Z),
    WEST(4, -1, 0, 0, Axis.X),
    EAST(5, 1, 0, 0, Axis.X);

    public enum Axis { X, Y, Z }

    private static final Direction[] HORIZONTAL = {NORTH, EAST, SOUTH, WEST};

    /* Geteilte, unveraenderliche Kopien fuer allokationsfreie Read-only-Iteration in heissen
       Pfaden (Fluid-Tick, Nachbar-Updates). NIEMALS mutieren. horizontal()/values() unten
       liefern weiterhin defensive Kopien fuer externe Aufrufer. */
    private static final Direction[] VALUES = values();
    private static final Direction[] HORIZONTAL_VALUES = {NORTH, EAST, SOUTH, WEST};

    private final int faceIndex;
    private final int dx, dy, dz;
    private final Axis axis;

    Direction(int faceIndex, int dx, int dy, int dz, Axis axis) {
        this.faceIndex = faceIndex;
        this.dx = dx;
        this.dy = dy;
        this.dz = dz;
        this.axis = axis;
    }

    public int faceIndex() { return faceIndex; }
    public int offsetX() { return dx; }
    public int offsetY() { return dy; }
    public int offsetZ() { return dz; }
    public Axis axis() { return axis; }

    public Direction opposite() {
        return switch (this) {
            case UP -> DOWN;
            case DOWN -> UP;
            case NORTH -> SOUTH;
            case SOUTH -> NORTH;
            case WEST -> EAST;
            case EAST -> WEST;
        };
    }

    /** Im Uhrzeigersinn um die Y-Achse (von oben betrachtet): N->E->S->W->N. */
    public Direction rotateYCW() {
        return switch (this) {
            case NORTH -> EAST;
            case EAST -> SOUTH;
            case SOUTH -> WEST;
            case WEST -> NORTH;
            default -> this;
        };
    }

    /** Gegen den Uhrzeigersinn um die Y-Achse: N->W->S->E->N. */
    public Direction rotateYCCW() {
        return switch (this) {
            case NORTH -> WEST;
            case WEST -> SOUTH;
            case SOUTH -> EAST;
            case EAST -> NORTH;
            default -> this;
        };
    }

    /** Die vier horizontalen Richtungen in der Reihenfolge N, E, S, W. */
    public static Direction[] horizontal() {
        return HORIZONTAL.clone();
    }

    /**
     * Geteiltes, unveraenderliches Array der vier horizontalen Richtungen (N, E, S, W) fuer
     * allokationsfreie Read-only-Iteration in heissen Pfaden. Das Ergebnis NIEMALS mutieren.
     */
    public static Direction[] horizontalValues() {
        return HORIZONTAL_VALUES;
    }

    /**
     * Geteiltes, unveraenderliches Array aller sechs Richtungen fuer allokationsfreie
     * Read-only-Iteration in heissen Pfaden (vermeidet den values()-Klon). NIEMALS mutieren.
     */
    public static Direction[] sharedValues() {
        return VALUES;
    }

    /**
     * Horizontale Blickrichtung des Spielers. Passend zur Kamera-Konvention
     * (Camera.getDirection): yaw=0 -> NORTH(-z), 90 -> EAST(+x), 180 -> SOUTH,
     * 270 -> WEST.
     */
    public static Direction fromYaw(float yaw) {
        int index = Math.floorMod(Math.round(yaw / 90.0F), 4);
        return switch (index) {
            case 0 -> NORTH;
            case 1 -> EAST;
            case 2 -> SOUTH;
            default -> WEST;
        };
    }
}
