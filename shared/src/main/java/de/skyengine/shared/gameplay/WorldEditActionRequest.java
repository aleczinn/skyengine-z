package de.skyengine.shared.gameplay;

import java.util.Objects;

/** Semantic structure-wand input; authoritative editor state remains on the server. */
public record WorldEditActionRequest(long actionId, Action action, String dimension,
                                     int x, int y, int z, int direction) {
    public WorldEditActionRequest {
        if (actionId < 0 || y < 0 || y >= 512 || direction < -1 || direction > 1) {
            throw new IllegalArgumentException("Invalid WorldEdit action");
        }
        Objects.requireNonNull(action);
        Objects.requireNonNull(dimension);
    }

    public enum Action { PRIMARY_CLICK, SECONDARY_CLICK, CYCLE_TOOL_MODE, CLEAR_SELECTION }
}
