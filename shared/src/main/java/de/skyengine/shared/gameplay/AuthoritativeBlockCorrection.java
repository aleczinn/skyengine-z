package de.skyengine.shared.gameplay;

import java.util.Objects;

/** Small action-scoped correction; it deliberately does not advance a whole chunk revision. */
public record AuthoritativeBlockCorrection(String dimension, int x, int y, int z, int stateId) {
    public AuthoritativeBlockCorrection {
        Objects.requireNonNull(dimension);
        if (y < 0 || y >= 512 || stateId < 0) throw new IllegalArgumentException("Invalid block correction");
    }
}
