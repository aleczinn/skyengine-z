package de.skyengine.game.world.block.entity;

public enum EnergySideMode {
    INPUT,
    OUTPUT,
    DISABLED;

    public EnergySideMode next() {
        return values()[(ordinal() + 1) % values().length];
    }
}
