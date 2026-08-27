package de.skyengine.game.world.block.entity;

import de.skyengine.game.world.block.BlockPos;
import de.skyengine.game.world.block.state.BlockState;

/**
 * Fabrik + Metadaten für eine BlockEntity-Art. Registriert in
 * {@code Registries.BLOCK_ENTITY}; ein Block verweist über seinen
 * {@link de.skyengine.game.world.block.archetype.BlockConfig} auf seinen Typ.
 *
 * @param <T> konkrete BlockEntity-Klasse
 */
public final class BlockEntityType<T extends BlockEntity> {

    /** Die Factory bekommt den Typ durchgereicht (BlockEntity braucht ihn im Konstruktor). */
    @FunctionalInterface
    public interface Factory<T extends BlockEntity> {
        T create(BlockEntityType<?> type, BlockPos pos, BlockState state);
    }

    private final Factory<T> factory;
    private final boolean ticking;
    private final boolean structureSerializable;

    public BlockEntityType(Factory<T> factory, boolean ticking) {
        this(factory, ticking, true);
    }

    public BlockEntityType(Factory<T> factory, boolean ticking, boolean structureSerializable) {
        this.factory = factory;
        this.ticking = ticking;
        this.structureSerializable = structureSerializable;
    }

    public T create(BlockPos pos, BlockState state) {
        return factory.create(this, pos, state);
    }

    public boolean isTicking() {
        return ticking;
    }

    /** Kurzlebige technische BlockEntities (z.B. bewegte Kolben) sind keine Template-Daten. */
    public boolean isStructureSerializable() {
        return structureSerializable;
    }
}
