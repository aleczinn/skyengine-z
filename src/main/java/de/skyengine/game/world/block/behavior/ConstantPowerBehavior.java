package de.skyengine.game.world.block.behavior;

import de.skyengine.game.world.World;
import de.skyengine.game.world.block.Direction;
import de.skyengine.game.world.block.state.BlockState;

/**
 * Konstante Signalquelle (Redstone-Block): schwaches Signal in alle Richtungen, immer an.
 * Bewusst KEIN starkes Signal — wie in Vanilla aktiviert ein Redstone-Block seine Nachbarn,
 * macht aber keinen angrenzenden opaken Block zur Quelle. Kommt aus dem JSON-Feld
 * {@code redstone_power}.
 */
public record ConstantPowerBehavior(int level) implements BlockBehavior {

    @Override
    public int weakPower(World world, int x, int y, int z, BlockState state, Direction side) {
        return this.level;
    }

    @Override
    public boolean connectsRedstoneWire(BlockState state, Direction side) {
        return true;
    }
}
