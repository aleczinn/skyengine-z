package de.skyengine.game.world.block.entity;

import de.skyengine.game.world.block.BlockPos;

/** Persistenter Comparator-Ausgang entsprechend Vanillas ComparatorBlockEntity. */
public final class ComparatorBlockEntity extends BlockEntity {

    private int outputSignal;

    public ComparatorBlockEntity(BlockEntityType<?> type, BlockPos pos) {
        super(type, pos);
    }

    public int getOutputSignal() {
        return this.outputSignal;
    }

    public void setOutputSignal(int outputSignal) {
        /* Vanilla übernimmt auch den NBT-Wert ungeclamped; reguläre Berechnung liefert 0..15. */
        this.outputSignal = outputSignal;
    }

    @Override
    public void load(DataTag tag) {
        this.setOutputSignal(tag.getInt("OutputSignal", 0));
    }

    @Override
    public void save(DataTag tag) {
        tag.putInt("OutputSignal", this.outputSignal);
    }
}
