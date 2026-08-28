package de.skyengine.game.world.block.entity;

import de.skyengine.game.world.block.BlockPos;
import de.skyengine.game.world.block.Blocks;
import de.skyengine.game.world.block.Direction;
import de.skyengine.game.world.block.state.BlockState;
import de.skyengine.game.world.block.state.Properties;
import de.skyengine.game.world.item.Item;
import de.skyengine.game.world.item.ItemStack;
import de.skyengine.game.world.recipe.RecipeManager;

import java.util.Optional;

/** Solid-fuel-only version of Mekanism's heat generator. */
public final class CoalGeneratorBlockEntity extends BlockEntity {
    public static final long CAPACITY = 64_000L;
    public static final long PRODUCTION = 80L;
    public static final long MAX_OUTPUT = 160L;

    private final SimpleEnergyStorage energy = new SimpleEnergyStorage(
            CAPACITY, PRODUCTION, MAX_OUTPUT, this::setChanged);
    private final SimpleItemStorage inventory = new SimpleItemStorage(1, this::setChanged);
    private final ItemStorage fuelView = new FuelView();
    private final EnergyStorage output = new OutputAccess();
    private int burnTime;
    private int burnDuration;
    private boolean producing;

    public CoalGeneratorBlockEntity(BlockEntityType<?> type, BlockPos pos) { super(type, pos); }

    public ItemStorage getInventory() { return this.inventory; }
    public long getEnergy() { return this.energy.getEnergy(); }
    public long getCapacity() { return CAPACITY; }
    public int getBurnTime() { return this.burnTime; }
    public int getBurnDuration() { return this.burnDuration; }
    public boolean isProducing() { return this.producing; }

    @Override public void tick() {
        if (this.world == null) return;
        boolean wasProducing = this.producing;
        this.producing = false;
        if (this.energy.receive(PRODUCTION, true) == PRODUCTION) {
            if (this.burnTime == 0) ignite();
            if (this.burnTime > 0) {
                this.burnTime--;
                this.energy.receive(PRODUCTION, false);
                this.producing = true;
            }
        }
        if (wasProducing != this.producing) updateLit(this.producing);
        if (wasProducing || this.producing) this.markDirty();
    }

    private void ignite() {
        ItemStack fuel = this.inventory.get(0);
        int duration = RecipeManager.get().fuels().burnTime(RecipeManager.SOLID_FUEL, fuel);
        if (duration <= 0) return;
        Item consumed = fuel.getItem();
        this.inventory.extract(0, 1);
        this.burnTime = duration;
        this.burnDuration = duration;
        Item remainder = consumed.getCraftingRemainder();
        if (remainder != null) {
            ItemStack remaining = this.inventory.insert(new ItemStack(remainder, 1));
            if (!remaining.isEmpty()) this.world.spawnItem(
                    this.pos.x() + .5, this.pos.y() + .5, this.pos.z() + .5, remaining);
        }
    }

    private void updateLit(boolean lit) {
        BlockState state = Blocks.getState(this.world.getBlock(this.pos.x(), this.pos.y(), this.pos.z()));
        if (state.getValues().containsKey(Properties.LIT) && state.get(Properties.LIT) != lit) {
            this.world.setBlock(this.pos.x(), this.pos.y(), this.pos.z(), state.with(Properties.LIT, lit).getId(), true);
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public <C> Optional<C> getCapability(Capability<C> capability, Direction side) {
        if (capability == Capabilities.ITEM_STORAGE) return Optional.of((C) this.fuelView);
        if (capability == Capabilities.ENERGY && side == facing()) return Optional.of((C) this.output);
        return Optional.empty();
    }

    private Direction facing() {
        if (this.world == null) return Direction.NORTH;
        BlockState state = Blocks.getState(this.world.getBlock(this.pos.x(), this.pos.y(), this.pos.z()));
        return state.getValues().containsKey(Properties.FACING) ? state.get(Properties.FACING) : Direction.NORTH;
    }

    @Override public void save(DataTag tag) {
        DataTag inventoryTag = new DataTag();
        this.inventory.save(inventoryTag);
        tag.putTag("inventory", inventoryTag);
        tag.putLong("energy", this.energy.getEnergy());
        tag.putInt("burn_time", this.burnTime);
        tag.putInt("burn_duration", this.burnDuration);
    }

    @Override public void load(DataTag tag) {
        this.inventory.load(tag.getTag("inventory"));
        this.energy.setEnergy(tag.getLong("energy", 0));
        this.burnTime = Math.max(0, tag.getInt("burn_time", 0));
        this.burnDuration = Math.max(0, tag.getInt("burn_duration", 0));
    }

    private final class OutputAccess implements EnergyStorage {
        @Override public long receive(long amount, boolean simulate) { return 0; }
        @Override public long extract(long amount, boolean simulate) { return energy.extract(amount, simulate); }
        @Override public long getEnergy() { return energy.getEnergy(); }
        @Override public long getCapacity() { return energy.getCapacity(); }
        @Override public long getMaxReceive() { return 0; }
        @Override public long getMaxExtract() { return MAX_OUTPUT; }
    }

    private final class FuelView implements ItemStorage {
        @Override public int size() { return 1; }
        @Override public ItemStack get(int slot) { return inventory.get(0); }
        @Override public void set(int slot, ItemStack stack) {
            if (stack == null || stack.isEmpty() || validFuel(stack)) inventory.set(0, stack);
        }
        @Override public ItemStack insert(ItemStack stack) {
            return validFuel(stack) ? inventory.insert(stack) : stack;
        }
        @Override public ItemStack extract(int slot, int amount) { return ItemStack.EMPTY; }
        @Override public void setChanged() { inventory.setChanged(); }
    }

    private static boolean validFuel(ItemStack stack) {
        return stack != null && !stack.isEmpty()
                && RecipeManager.get().fuels().burnTime(RecipeManager.SOLID_FUEL, stack) > 0;
    }
}
