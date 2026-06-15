package de.skyengine.game.world.block.behavior;

import de.skyengine.game.world.World;

/**
 * Kontext einer Block-Platzierung: Zielfeld, getroffene Fläche, relativer Treffer-Y
 * (0..1) und Spieler-Yaw. Wird an {@link BlockBehavior#onPlace} übergeben.
 */
public record PlacementContext(World world, int x, int y, int z,
                               int faceX, int faceY, int faceZ,
                               double hitY, float playerYaw) {}
