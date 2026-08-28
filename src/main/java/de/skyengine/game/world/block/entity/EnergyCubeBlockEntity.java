package de.skyengine.game.world.block.entity;

import de.skyengine.game.world.block.BlockPos;
import de.skyengine.game.world.block.Blocks;
import de.skyengine.game.world.block.Direction;
import de.skyengine.game.world.block.state.BlockState;
import de.skyengine.game.world.block.state.Properties;
import de.skyengine.game.world.block.network.DirectEnergyOutput;
import de.skyengine.game.world.item.ItemStack;

import java.util.Arrays;
import java.util.Optional;

public final class EnergyCubeBlockEntity extends BlockEntity implements PortableBlockEntity, DirectEnergyOutput {
    public static final long CAPACITY = 1_600_000L;
    public static final long TRANSFER_RATE = 1_600L;

    private final SimpleEnergyStorage energy = new SimpleEnergyStorage(
            CAPACITY, TRANSFER_RATE, TRANSFER_RATE, this::setChanged);
    private final EnergySideMode[] sideModes = new EnergySideMode[RelativeSide.values().length];
    private final EnergyStorage input = new Access(true, false);
    private final EnergyStorage output = new Access(false, true);
    private final SimpleItemStorage inventory = new SimpleItemStorage(2, this::setChanged);
    private long receivedThisTick;
    private long extractedThisTick;
    private boolean autoEject = true;

    public EnergyCubeBlockEntity(BlockEntityType<?> type, BlockPos pos) {
        super(type, pos);
        Arrays.fill(this.sideModes, EnergySideMode.INPUT);
        this.sideModes[RelativeSide.FRONT.ordinal()] = EnergySideMode.OUTPUT;
    }

    public long getEnergy() { return this.energy.getEnergy(); }
    public long getCapacity() { return this.energy.getCapacity(); }
    public ItemStorage getInventory() { return this.inventory; }
    public boolean isAutoEject() { return this.autoEject; }
    public EnergySideMode getSideMode(RelativeSide side) { return this.sideModes[side.ordinal()]; }

    public void cycleSideMode(RelativeSide side) {
        setSideMode(side, getSideMode(side).next());
    }

    public void cycleSideMode(RelativeSide side, boolean backwards) {
        setSideMode(side, backwards ? getSideMode(side).previous() : getSideMode(side).next());
    }

    public void setAllSideModes(EnergySideMode mode) {
        if (mode == null) return;
        for (RelativeSide side : RelativeSide.values()) this.sideModes[side.ordinal()] = mode;
        configurationChanged();
    }

    public void setAutoEject(boolean autoEject) {
        if (this.autoEject == autoEject) return;
        this.autoEject = autoEject;
        configurationChanged();
    }

    private void configurationChanged() {
        this.setChanged();
        if (this.world != null) {
            this.world.getEnergyNetworks().invalidate();
            this.world.updateNeighbors(this.pos.x(), this.pos.y(), this.pos.z());
        }
    }

    public void setSideMode(RelativeSide side, EnergySideMode mode) {
        if (mode == null || this.sideModes[side.ordinal()] == mode) return;
        this.sideModes[side.ordinal()] = mode;
        configurationChanged();
    }

    @Override public void tick() {
        this.receivedThisTick = 0;
        this.extractedThisTick = 0;
        transferFromItem(this.inventory.get(0));
        transferToItem(this.inventory.get(1));
    }

    private void transferFromItem(ItemStack stack) {
        EnergyStorage item = itemEnergy(stack);
        if (item == null || !item.canExtract()) return;
        long offered = item.extract(TRANSFER_RATE, true);
        long accepted = this.input.receive(offered, true);
        if (accepted <= 0) return;
        long extracted = item.extract(accepted, false);
        this.input.receive(extracted, false);
        this.inventory.setChanged();
    }

    private void transferToItem(ItemStack stack) {
        EnergyStorage item = itemEnergy(stack);
        if (item == null || !item.canReceive()) return;
        long offered = this.output.extract(TRANSFER_RATE, true);
        long accepted = item.receive(offered, true);
        if (accepted <= 0) return;
        long extracted = this.output.extract(accepted, false);
        item.receive(extracted, false);
        this.inventory.setChanged();
    }

    public static EnergyStorage itemEnergy(ItemStack stack) {
        return stack == null || stack.isEmpty() ? null
                : stack.getItem().getCapability(Capabilities.ENERGY, stack).orElse(null);
    }

    @Override public boolean allowsDirectEnergyOutput(Direction side) {
        return this.autoEject && getSideMode(RelativeSide.fromWorld(this.facing(), side)) == EnergySideMode.OUTPUT;
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
        tag.putBoolean("auto_eject", this.autoEject);
        DataTag inventoryTag = new DataTag();
        this.inventory.save(inventoryTag);
        tag.putTag("inventory", inventoryTag);
    }

    @Override public void load(DataTag tag) {
        this.energy.setEnergy(tag.getLong("energy", 0));
        loadSides(tag);
        this.autoEject = tag.getBoolean("auto_eject", true);
        this.inventory.load(tag.getTag("inventory"));
    }

    @Override public void savePortable(DataTag tag) {
        tag.putLong("energy", this.energy.getEnergy());
        saveSides(tag);
        tag.putBoolean("auto_eject", this.autoEject);
    }
    @Override public void loadPortable(DataTag tag) {
        this.energy.setEnergy(tag.getLong("energy", 0));
        loadSides(tag);
        this.autoEject = tag.getBoolean("auto_eject", true);
    }

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
            if (!this.receive) return 0;
            long accepted = energy.receive(Math.min(amount, Math.max(0, TRANSFER_RATE - receivedThisTick)), simulate);
            if (!simulate) receivedThisTick += accepted;
            return accepted;
        }
        @Override public long extract(long amount, boolean simulate) {
            if (!this.extract) return 0;
            long extracted = energy.extract(Math.min(amount, Math.max(0, TRANSFER_RATE - extractedThisTick)), simulate);
            if (!simulate) extractedThisTick += extracted;
            return extracted;
        }
        @Override public long getEnergy() { return energy.getEnergy(); }
        @Override public long getCapacity() { return energy.getCapacity(); }
        @Override public long getMaxReceive() { return this.receive ? TRANSFER_RATE : 0; }
        @Override public long getMaxExtract() { return this.extract ? TRANSFER_RATE : 0; }
    }
}
