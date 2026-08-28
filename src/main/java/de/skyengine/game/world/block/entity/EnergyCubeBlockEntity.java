package de.skyengine.game.world.block.entity;

import de.skyengine.game.world.block.BlockPos;
import de.skyengine.game.world.block.Blocks;
import de.skyengine.game.world.block.Direction;
import de.skyengine.game.world.block.state.BlockState;
import de.skyengine.game.world.block.state.Properties;

import java.util.Arrays;
import java.util.Optional;

public final class EnergyCubeBlockEntity extends BlockEntity implements PortableBlockEntity {
    public static final long CAPACITY = 1_600_000L;
    public static final long TRANSFER_RATE = 1_600L;

    private final SimpleEnergyStorage energy = new SimpleEnergyStorage(
            CAPACITY, TRANSFER_RATE, TRANSFER_RATE, this::setChanged);
    private final EnergySideMode[] sideModes = new EnergySideMode[RelativeSide.values().length];
    private final EnergyStorage input = new Access(true, false);
    private final EnergyStorage output = new Access(false, true);

    public EnergyCubeBlockEntity(BlockEntityType<?> type, BlockPos pos) {
        super(type, pos);
        Arrays.fill(this.sideModes, EnergySideMode.INPUT);
        this.sideModes[RelativeSide.FRONT.ordinal()] = EnergySideMode.OUTPUT;
    }

    public long getEnergy() { return this.energy.getEnergy(); }
    public long getCapacity() { return this.energy.getCapacity(); }
    public EnergySideMode getSideMode(RelativeSide side) { return this.sideModes[side.ordinal()]; }

    public void cycleSideMode(RelativeSide side) {
        setSideMode(side, getSideMode(side).next());
    }

    public void setSideMode(RelativeSide side, EnergySideMode mode) {
        if (mode == null || this.sideModes[side.ordinal()] == mode) return;
        this.sideModes[side.ordinal()] = mode;
        this.setChanged();
        if (this.world != null) {
            this.world.getEnergyNetworks().invalidate();
            this.world.updateNeighbors(this.pos.x(), this.pos.y(), this.pos.z());
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public <C> Optional<C> getCapability(Capability<C> capability, Direction side) {
        if (capability != Capabilities.ENERGY || side == null) return Optional.empty();
        RelativeSide relative = RelativeSide.fromWorld(this.facing(), side);
        return switch (getSideMode(relative)) {
            case INPUT -> Optional.of((C) this.input);
            case OUTPUT -> Optional.of((C) this.output);
            case DISABLED -> Optional.empty();
        };
    }

    public Direction getFacing() {
        if (this.world == null) return Direction.NORTH;
        BlockState state = Blocks.getState(this.world.getBlock(this.pos.x(), this.pos.y(), this.pos.z()));
        return state.getValues().containsKey(Properties.FACING_ALL)
                ? state.get(Properties.FACING_ALL) : Direction.NORTH;
    }

    private Direction facing() { return getFacing(); }

    @Override public void save(DataTag tag) {
        tag.putLong("energy", this.energy.getEnergy());
        saveSides(tag);
    }

    @Override public void load(DataTag tag) {
        this.energy.setEnergy(tag.getLong("energy", 0));
        loadSides(tag);
    }

    @Override public void savePortable(DataTag tag) { save(tag); }
    @Override public void loadPortable(DataTag tag) { load(tag); }

    private void saveSides(DataTag tag) {
        for (RelativeSide side : RelativeSide.values()) {
            tag.putString("side_" + side.name().toLowerCase(), getSideMode(side).name().toLowerCase());
        }
    }

    private void loadSides(DataTag tag) {
        for (RelativeSide side : RelativeSide.values()) {
            String value = tag.getString("side_" + side.name().toLowerCase(), null);
            if (value == null) continue;
            try { this.sideModes[side.ordinal()] = EnergySideMode.valueOf(value.toUpperCase()); }
            catch (IllegalArgumentException ignored) { }
        }
    }

    private final class Access implements EnergyStorage {
        private final boolean receive;
        private final boolean extract;
        private Access(boolean receive, boolean extract) { this.receive = receive; this.extract = extract; }
        @Override public long receive(long amount, boolean simulate) {
            return this.receive ? energy.receive(amount, simulate) : 0;
        }
        @Override public long extract(long amount, boolean simulate) {
            return this.extract ? energy.extract(amount, simulate) : 0;
        }
        @Override public long getEnergy() { return energy.getEnergy(); }
        @Override public long getCapacity() { return energy.getCapacity(); }
        @Override public long getMaxReceive() { return this.receive ? TRANSFER_RATE : 0; }
        @Override public long getMaxExtract() { return this.extract ? TRANSFER_RATE : 0; }
    }
}
