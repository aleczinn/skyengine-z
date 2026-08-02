package de.skyengine.game.world.block.behavior;

import de.skyengine.game.world.World;
import de.skyengine.game.world.block.Direction;
import de.skyengine.game.world.block.state.BlockState;
import de.skyengine.game.world.block.state.Properties;
import de.skyengine.game.world.redstone.RedstonePower;

/**
 * Redstone-Fackel, der Inverter: aus, sobald ihr Trägerblock ein Signal führt — mit einem
 * Redstone-Tick (2 Game-Ticks) Verzögerung über den Scheduler. Leuchtend gibt sie 15 in
 * alle Richtungen außer zum Träger ab, stark nur nach OBEN (deshalb schaltet eine Fackel
 * unter einem Block alles über diesem Block). Kein Burnout (bewusst weggelassen,
 * additiv nachrüstbar).
 */
public final class RedstoneTorchBehavior implements BlockBehavior {

    @Override
    public BlockState onNeighborUpdate(World world, int x, int y, int z, BlockState state) {
        if (shouldBeLit(world, x, y, z, state) != state.get(Properties.LIT)
                && !world.isTickScheduled(x, y, z)) {
            world.scheduleTick(x, y, z, 2);
        }
        return state;
    }

    @Override
    public void scheduledTick(World world, int x, int y, int z, BlockState state) {
        /* Tolerantes Feuern: neu prüfen — hat sich das Signal zurückgedreht, passiert nichts. */
        boolean lit = shouldBeLit(world, x, y, z, state);
        if (lit == state.get(Properties.LIT)) return;
        world.setBlock(x, y, z, state.with(Properties.LIT, lit).getId(), true);
        /* Starkes Ziel oben: dessen Nachbarn erfahren die Flanke nur über den zweiten Ring. */
        world.updateNeighbors(x, y + 1, z);
    }

    /** An, solange der Trägerblock KEIN Signal in die Fackel speist (Inverter). */
    private static boolean shouldBeLit(World world, int x, int y, int z, BlockState state) {
        Direction support = ButtonBehavior.supportDirection(state);
        int sx = x + support.offsetX(), sy = y + support.offsetY(), sz = z + support.offsetZ();
        return RedstonePower.emittedSignal(world, sx, sy, sz, support.opposite(), false) == 0;
    }

    @Override
    public int weakPower(World world, int x, int y, int z, BlockState state, Direction side) {
        if (!state.get(Properties.LIT)) return 0;
        return side == ButtonBehavior.supportDirection(state) ? 0 : 15;
    }

    @Override
    public int strongPower(World world, int x, int y, int z, BlockState state, Direction side) {
        return state.get(Properties.LIT) && side == Direction.UP ? 15 : 0;
    }

    @Override
    public boolean connectsRedstoneWire(BlockState state, Direction side) {
        return true;
    }
}
