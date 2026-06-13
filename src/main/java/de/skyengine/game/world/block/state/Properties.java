package de.skyengine.game.world.block.state;

import de.skyengine.game.world.block.Direction;

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

    /** Verbindungs-Properties für Zäune und Panes. */
    public static final Property<Boolean> NORTH = Property.ofBoolean("north");
    public static final Property<Boolean> EAST = Property.ofBoolean("east");
    public static final Property<Boolean> SOUTH = Property.ofBoolean("south");
    public static final Property<Boolean> WEST = Property.ofBoolean("west");

    /** Verbindungs-Property passend zur horizontalen Richtung. */
    public static Property<Boolean> connection(Direction direction) {
        return switch (direction) {
            case NORTH -> NORTH;
            case EAST -> EAST;
            case SOUTH -> SOUTH;
            case WEST -> WEST;
            default -> throw new IllegalArgumentException("Keine Verbindung für " + direction);
        };
    }

    private Properties() {}
}
