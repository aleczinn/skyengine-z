package de.skyengine.game.world.block.entity;

import de.skyengine.game.world.block.BlockPos;
import de.skyengine.game.world.block.Blocks;
import de.skyengine.game.world.block.Direction;
import de.skyengine.game.world.block.state.BlockState;
import de.skyengine.game.world.block.state.Properties;
import de.skyengine.game.world.item.Item;
import de.skyengine.game.world.item.ItemStack;
import de.skyengine.game.world.recipe.ProcessingRecipe;
import de.skyengine.game.world.recipe.RecipeManager;

import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

/** Tickender Standardofen mit Material-, Brennstoff- und Ausgabeslot. */
public final class FurnaceBlockEntity extends BlockEntity {

    public static final int INPUT = 0, FUEL = 1, OUTPUT = 2, SLOTS = 3;
    private final SimpleItemStorage inventory;
    private final ItemStorage inputView;
    private final ItemStorage fuelView;
    private final ItemStorage outputView;
    private ProcessingRecipe cachedRecipe;
    private boolean recipeDirty = true;
    private int burnTime;
    private int burnDuration;
    private int cookProgress;
    private int cookDuration = 200;

    public FurnaceBlockEntity(BlockEntityType<?> type, BlockPos pos) {
        super(type, pos);
        this.inventory = new SimpleItemStorage(SLOTS, this::inventoryChanged);
        this.inputView = new SlotView(INPUT, stack -> this.findRecipe(stack) != null, true);
        this.fuelView = new SlotView(FUEL,
                stack -> RecipeManager.get().fuels().burnTime(RecipeManager.SOLID_FUEL, stack) > 0, true);
        this.outputView = new SlotView(OUTPUT, stack -> false, false);
    }

    public ItemStorage getInventory() { return this.inventory; }
    public int getBurnTime() { return this.burnTime; }
    public int getBurnDuration() { return this.burnDuration; }
    public int getCookProgress() { return this.cookProgress; }
    public int getCookDuration() { return this.cookDuration; }

    private void inventoryChanged() {
        this.recipeDirty = true;
        this.setChanged();
    }

    @Override
    public void tick() {
        if (this.world == null) return;
        boolean wasBurning = this.burnTime > 0;
        if (this.burnTime > 0) this.burnTime--;

        ProcessingRecipe recipe = this.currentRecipe();
        boolean canProcess = recipe != null && this.canOutput(recipe);
        if (this.burnTime == 0 && canProcess) this.ignite();

        if (this.burnTime > 0 && canProcess) {
            this.cookDuration = recipe.duration();
            if (++this.cookProgress >= this.cookDuration) {
                this.finish(recipe);
                this.cookProgress = 0;
            }
        } else if (!canProcess) {
            this.cookProgress = 0;
        }

        boolean burning = this.burnTime > 0;
        if (wasBurning != burning) this.updateLit(burning);
        if (wasBurning || burning || this.cookProgress > 0) this.markDirty();
    }

    private ProcessingRecipe currentRecipe() {
        if (!this.recipeDirty) return this.cachedRecipe;
        this.recipeDirty = false;
        this.cachedRecipe = this.findRecipe(this.inventory.get(INPUT));
        return this.cachedRecipe;
    }

    private ProcessingRecipe findRecipe(ItemStack input) {
        if (input == null || input.isEmpty()) return null;
        ProcessingRecipe recipe = RecipeManager.get().findProcessing(RecipeManager.FURNACE, List.of(input));
        return recipe != null && recipe.inputs().size() == 1 && recipe.outputs().size() == 1 ? recipe : null;
    }

    private boolean canOutput(ProcessingRecipe recipe) {
        ItemStack result = recipe.outputs().getFirst();
        ItemStack output = this.inventory.get(OUTPUT);
        return output.isEmpty() ? result.getCount() <= result.getMaxStackSize()
                : output.canStackWith(result) && output.getCount() + result.getCount() <= output.getMaxStackSize();
    }

    private void ignite() {
        ItemStack fuel = this.inventory.get(FUEL);
        int duration = RecipeManager.get().fuels().burnTime(RecipeManager.SOLID_FUEL, fuel);
        if (duration <= 0) return;
        Item consumed = fuel.getItem();
        this.inventory.extract(FUEL, 1);
        this.burnTime = duration;
        this.burnDuration = duration;
        Item remainder = consumed.getCraftingRemainder();
        if (remainder != null) this.placeRemainder(FUEL, new ItemStack(remainder, 1));
    }

    private void finish(ProcessingRecipe recipe) {
        ProcessingRecipe.CountedIngredient ingredient = recipe.inputs().getFirst();
        Item consumed = this.inventory.get(INPUT).getItem();
        this.inventory.extract(INPUT, ingredient.count());
        Item remainder = consumed.getCraftingRemainder();
        if (remainder != null) this.placeRemainder(INPUT, new ItemStack(remainder, 1));

        ItemStack result = recipe.outputs().getFirst().copy();
        ItemStack output = this.inventory.get(OUTPUT);
        if (output.isEmpty()) this.inventory.set(OUTPUT, result);
        else {
            output.setCount(output.getCount() + result.getCount());
            this.inventory.setChanged();
        }
    }

    private void placeRemainder(int preferredSlot, ItemStack remainder) {
        if (this.inventory.get(preferredSlot).isEmpty()) {
            this.inventory.set(preferredSlot, remainder);
        } else if (this.world != null) {
            this.world.spawnItem(this.pos.x() + 0.5, this.pos.y() + 0.5, this.pos.z() + 0.5, remainder);
        }
    }

    private void updateLit(boolean lit) {
        BlockState state = Blocks.getState(this.world.getBlock(this.pos.x(), this.pos.y(), this.pos.z()));
        if (!state.getValues().containsKey(Properties.LIT) || state.get(Properties.LIT) == lit) return;
        this.world.setBlock(this.pos.x(), this.pos.y(), this.pos.z(), state.with(Properties.LIT, lit).getId(), true);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <C> Optional<C> getCapability(Capability<C> capability, Direction side) {
        if (capability != Capabilities.ITEM_STORAGE) return Optional.empty();
        ItemStorage view = side == Direction.UP ? this.inputView
                : side == Direction.DOWN ? this.outputView : this.fuelView;
        return Optional.of((C) view);
    }

    @Override public void save(DataTag tag) {
        DataTag inventoryTag = new DataTag();
        this.inventory.save(inventoryTag);
        tag.putTag("inventory", inventoryTag);
        tag.putInt("burn_time", this.burnTime);
        tag.putInt("burn_duration", this.burnDuration);
        tag.putInt("cook_progress", this.cookProgress);
        tag.putInt("cook_duration", this.cookDuration);
    }

    @Override public void load(DataTag tag) {
        this.inventory.load(tag.getTag("inventory"));
        this.burnTime = Math.max(0, tag.getInt("burn_time", 0));
        this.burnDuration = Math.max(0, tag.getInt("burn_duration", 0));
        this.cookProgress = Math.max(0, tag.getInt("cook_progress", 0));
        this.cookDuration = Math.max(1, tag.getInt("cook_duration", 200));
        this.recipeDirty = true;
    }

    private final class SlotView implements ItemStorage {
        private final int slot;
        private final Predicate<ItemStack> accepts;
        private final boolean insertable;

        private SlotView(int slot, Predicate<ItemStack> accepts, boolean insertable) {
            this.slot = slot;
            this.accepts = accepts;
            this.insertable = insertable;
        }
        @Override public int size() { return 1; }
        @Override public ItemStack get(int ignored) { return inventory.get(this.slot); }
        @Override public void set(int ignored, ItemStack stack) {
            if (this.insertable && (stack == null || stack.isEmpty() || this.accepts.test(stack))) inventory.set(this.slot, stack);
        }
        @Override public ItemStack insert(ItemStack stack) {
            if (!this.insertable || stack == null || stack.isEmpty() || !this.accepts.test(stack)) return stack;
            ItemStack remaining = stack.copy();
            ItemStack existing = inventory.get(this.slot);
            if (existing.isEmpty()) inventory.set(this.slot, remaining.split(remaining.getMaxStackSize()));
            else if (existing.canStackWith(remaining)) {
                int move = Math.min(existing.getMaxStackSize() - existing.getCount(), remaining.getCount());
                existing.setCount(existing.getCount() + move);
                remaining.setCount(remaining.getCount() - move);
                if (move > 0) inventory.setChanged();
            }
            return remaining.isEmpty() ? ItemStack.EMPTY : remaining;
        }
        @Override public ItemStack extract(int ignored, int amount) {
            return this.slot == OUTPUT ? inventory.extract(this.slot, amount) : ItemStack.EMPTY;
        }
        @Override public void setChanged() { inventory.setChanged(); }
    }
}
