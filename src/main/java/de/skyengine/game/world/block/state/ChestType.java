package de.skyengine.game.world.block.state;

import de.skyengine.game.world.block.Direction;

/**
 * Rolle einer Truhe in einer Doppeltruhe (wie MCs {@code ChestType}). SINGLE steht bewusst
 * zuerst: der erste Enum-Wert ist der Default-State, und eine frisch gesetzte oder aus einem
 * alten Save geladene Truhe ist eine Einzeltruhe.
 *
 * <p>LEFT/RIGHT sind aus Sicht des Betrachters VOR der Truhe gemeint.
 */
public enum ChestType {

    SINGLE,
    LEFT,
    RIGHT;

    /** Die jeweils andere Rolle (SINGLE bleibt SINGLE). */
    public ChestType opposite() {
        return switch (this) {
            case LEFT -> RIGHT;
            case RIGHT -> LEFT;
            case SINGLE -> SINGLE;
        };
    }

    /**
     * Richtung zur Partnerhälfte (MCs {@code ChestBlock.getConnectedDirection}); für SINGLE
     * bedeutungslos, deshalb nur mit LEFT/RIGHT aufrufen.
     */
    public static Direction connectedDirection(Direction facing, ChestType type) {
        return type == LEFT ? facing.rotateYCW() : facing.rotateYCCW();
    }
}
