package de.skyengine.game.world;

import de.skyengine.game.world.block.Identifier;

/** Dimensionsfaehiger, persistierbarer Standort eines Spielers. */
public record PlayerLocation(Identifier dimension, double x, double y, double z,
                             float yaw, float pitch) {

    public PlayerLocation {
        if (dimension == null || !Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)
                || !Float.isFinite(yaw) || !Float.isFinite(pitch)) {
            throw new IllegalArgumentException("Ungueltiger Spielerstandort");
        }
    }
}
