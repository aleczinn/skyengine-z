package de.skyengine.game.world.block.state;

import de.skyengine.game.world.block.Direction;

import java.util.ArrayList;
import java.util.List;

/**
 * Gemeinsame Property-Konstanten. Da {@link Property} per Identität verglichen
 * wird, MUSS jedes Property genau einmal existieren - deshalb hier zentral.
 */
public final class Properties {

    /** Horizontale Ausrichtung (N/E/S/W) für Treppen u.a. */
    public static final Property<Direction> FACING =
            Property.of("facing", List.of(Direction.horizontal()));

    public static final Property<SlabType> SLAB_TYPE =
            Property.ofEnum("type", SlabType.class);

    public static final Property<BlockHalf> HALF =
            Property.ofEnum("half", BlockHalf.class);

    public static final Property<StairShape> STAIR_SHAPE =
            Property.ofEnum("shape", StairShape.class);

    /** Achse (Pillar/Log: X/Y/Z) aus der Platzierungsfläche. */
    public static final Property<Direction.Axis> AXIS =
            Property.ofEnum("axis", Direction.Axis.class);

    /** Fluid-Stand 0..15 (0 = Quelle, 1..7 = fließend). */
    public static final Property<Integer> LEVEL = Property.of("level", levels());

    /** Fluid fällt als volle Säule nach unten (von oben gespeist). */
    public static final Property<Boolean> FALLING = Property.ofBoolean("falling");

    /** Offen/geschlossen (Türen, Trapdoors, Fence-Gates). */
    public static final Property<Boolean> OPEN = Property.ofBoolean("open");

    /** Türanschlag links/rechts. */
    public static final Property<DoorHinge> HINGE = Property.ofEnum("hinge", DoorHinge.class);

    private static List<Integer> levels() {
        List<Integer> list = new ArrayList<>(16);
        for (int i = 0; i < 16; i++) list.add(i);
        return list;
    }

    /** Verbindungs-Properties (Zäune, Panes, Walls, Pipes, Cables, Netzwerke). */
    public static final Property<Boolean> NORTH = Property.ofBoolean("north");
    public static final Property<Boolean> EAST = Property.ofBoolean("east");
    public static final Property<Boolean> SOUTH = Property.ofBoolean("south");
    public static final Property<Boolean> WEST = Property.ofBoolean("west");
    public static final Property<Boolean> UP = Property.ofBoolean("up");
    public static final Property<Boolean> DOWN = Property.ofBoolean("down");

    /** Verbindungs-Property passend zur Richtung (alle 6 Achsen). */
    public static Property<Boolean> connection(Direction direction) {
        return switch (direction) {
            case NORTH -> NORTH;
            case EAST -> EAST;
            case SOUTH -> SOUTH;
            case WEST -> WEST;
            case UP -> UP;
            case DOWN -> DOWN;
        };
    }

    private Properties() {}
}
