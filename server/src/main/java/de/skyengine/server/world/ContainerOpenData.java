package de.skyengine.server.world;

import de.skyengine.shared.gameplay.ContainerKind;

/** Descriptor returned by the authoritative world when a block use opens a menu. */
public record ContainerOpenData(int containerId, ContainerKind kind, int containerSlots, int rows,
                                String dimension, int x, int y, int z) { }
