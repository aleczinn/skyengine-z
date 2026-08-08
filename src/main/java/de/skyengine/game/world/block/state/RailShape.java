package de.skyengine.game.world.block.state;

/** Die zehn Vanilla-Schienenformen; Spezialschienen verwenden nur die ersten sechs. */
public enum RailShape {
    NORTH_SOUTH,
    EAST_WEST,
    ASCENDING_EAST,
    ASCENDING_WEST,
    ASCENDING_NORTH,
    ASCENDING_SOUTH,
    SOUTH_EAST,
    SOUTH_WEST,
    NORTH_WEST,
    NORTH_EAST;

    public boolean isAscending() {
        return ordinal() >= ASCENDING_EAST.ordinal() && ordinal() <= ASCENDING_SOUTH.ordinal();
    }

    public boolean isStraight() {
        return ordinal() <= ASCENDING_SOUTH.ordinal();
    }
}
