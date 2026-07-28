package de.skyengine.game.world.block.behavior;

import de.skyengine.game.world.World;

/**
 * Kontext einer Block-Platzierung: Zielfeld, getroffene Fläche, relativer Trefferpunkt
 * (hitX/hitY/hitZ, je 0..1 innerhalb des Zielfeldes), Spieler-Yaw und ob der Spieler dabei
 * sneakt. Wird an {@link BlockBehavior#onPlace} übergeben.
 *
 * <p>{@code sneaking} ist MCs „secondary use": es unterdrückt z.B. das automatische
 * Verschmelzen zweier Truhen zur Doppeltruhe.
 */
public record PlacementContext(World world, int x, int y, int z,
                               int faceX, int faceY, int faceZ,
                               double hitX, double hitY, double hitZ, float playerYaw,
                               boolean sneaking) {}
