package de.skyengine.server.world;

import de.skyengine.shared.gameplay.WorldSoundType;

public record WorldSoundEvent(String dimension, WorldSoundType type, int data,
                              double x, double y, double z) { }
