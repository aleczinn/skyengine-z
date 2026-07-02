package de.skyengine.game.world.block.behavior;

import de.skyengine.game.world.World;
import de.skyengine.game.world.block.Blocks;
import de.skyengine.game.world.block.state.BlockState;

/**
 * Verhalten für Cross-Pflanzen (Gras, Farn, Blumen): zerbricht, sobald die feste Stütze
 * darunter fehlt. Überschwemmung übernimmt das {@link FluidBehavior} (Fluid ersetzt die
 * Pflanze erst, wenn es wirklich in ihre Zelle fließt, und droppt dabei das Item).
 * Kein Drop bei Stützen-Verlust (vorerst).
 */
public final class PlantBehavior implements BlockBehavior {

    @Override
    public BlockState onNeighborUpdate(World world, int x, int y, int z, BlockState state) {
        if (canSurvive(world, x, y, z)) return state;
        return Blocks.getState(Blocks.AIR); // zerbricht
    }

    /** Überlebt nur auf einem festen Block. */
    private static boolean canSurvive(World world, int x, int y, int z) {
        return Blocks.getState(world.getBlock(x, y - 1, z)).isSolid();
    }
}
