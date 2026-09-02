package de.skyengine.shared.gameplay;

import java.util.Objects;

public record BlockActionRequest(long actionId, Action action, String dimension, int x, int y, int z,
                                 int face, int hand, int expectedStateId, int requestedStateId,
                                 int hitX, int hitY, int hitZ, boolean secondaryUse) {
    public BlockActionRequest {
        if (actionId < 0) throw new IllegalArgumentException("Negative action ID");
        Objects.requireNonNull(action); Objects.requireNonNull(dimension);
        if (y < 0 || y >= 512 || face < 0 || face > 5 || hand < 0 || hand > 1
                || expectedStateId < 0 || requestedStateId < -1 || hitX < 0 || hitX > 255
                || hitY < 0 || hitY > 255 || hitZ < 0 || hitZ > 255) {
            throw new IllegalArgumentException("Invalid block action");
        }
    }
    public BlockActionRequest(long actionId, Action action, String dimension, int x, int y, int z,
                              int face, int hand, int expectedStateId) {
        this(actionId, action, dimension, x, y, z, face, hand, expectedStateId,
                -1, 128, 128, 128, false);
    }
    public double relativeHitX() { return hitX / 255.0; }
    public double relativeHitY() { return hitY / 255.0; }
    public double relativeHitZ() { return hitZ / 255.0; }
    public enum Action { START_BREAK, CANCEL_BREAK, FINISH_BREAK, PLACE, INTERACT }
}
