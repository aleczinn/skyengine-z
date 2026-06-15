package de.skyengine.game.world.block.entity;

import de.skyengine.game.world.block.BlockPos;
import de.skyengine.game.world.block.state.BlockState;

import java.util.function.BiFunction;

/**
 * Fabrik + Metadaten für eine BlockEntity-Art. Registriert in
 * {@code Registries.BLOCK_ENTITY}; ein Block verweist über seinen
 * {@link de.skyengine.game.world.block.archetype.BlockConfig} auf seinen Typ.
 *
 * @param <T> konkrete BlockEntity-Klasse
 */
public final class BlockEntityType<T extends BlockEntity> {

    private final BiFunction<BlockPos, BlockState, T> factory;
    private final boolean ticking;

    public BlockEntityType(BiFunction<BlockPos, BlockState, T> factory, boolean ticking) {
        this.factory = factory;
        this.ticking = ticking;
    }

    public T create(BlockPos pos, BlockState state) {
        return factory.apply(pos, state);
    }

    public boolean isTicking() {
        return ticking;
    }
}
