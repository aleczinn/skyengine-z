package de.skyengine.game.world.block.entity;

import de.skyengine.game.entity.ItemEntity;
import de.skyengine.game.world.block.BlockPos;
import de.skyengine.game.world.block.Blocks;
import de.skyengine.game.world.block.Direction;
import de.skyengine.game.world.block.Identifier;
import de.skyengine.game.world.block.behavior.ExplosionBehavior;
import de.skyengine.game.world.block.state.BlockState;
import de.skyengine.game.world.block.state.Properties;
import de.skyengine.game.world.item.BlockItem;
import de.skyengine.game.world.item.BucketItem;
import de.skyengine.game.world.item.FlintAndSteelItem;
import de.skyengine.game.world.item.Item;
import de.skyengine.game.world.item.ItemStack;
import de.skyengine.game.world.item.ItemFrameItem;
import de.skyengine.game.world.item.MinecartItem;
import de.skyengine.game.world.item.Items;

import java.util.Optional;

/** 9-Slot-Inventar und Ausgabeoperation eines Dispensers bzw. Droppers. */
public final class DispenserBlockEntity extends BlockEntity {

    public static final int SLOTS = 9;
    private final SimpleItemStorage inventory;

    public DispenserBlockEntity(BlockEntityType<?> type, BlockPos pos) {
        super(type, pos);
        this.inventory = new SimpleItemStorage(SLOTS, this::setChanged);
    }

    public ItemStorage getInventory() {
        return this.inventory;
    }

    /** Aktiviert genau einen per Vanilla-Reservoir-Auswahl bestimmten belegten Slot. */
    public void activate(Direction facing, boolean dropper) {
        if (this.world == null) return;
        int slot = this.randomSlot();
        if (slot < 0) {
            this.world.playDispenserFailure(this.pos.x(), this.pos.y(), this.pos.z());
            return;
        }

        boolean success = dropper ? this.dropOne(slot, facing) : this.dispenseOne(slot, facing);
        if (success) this.setChanged();
    }

    /** Vanillas DispenserBlockEntity#getRandomSlot: gleichverteilte Reservoir-Auswahl. */
    int randomSlot() {
        int chosen = -1;
        int seen = 1;
        for (int slot = 0; slot < this.inventory.size(); slot++) {
            if (this.inventory.get(slot).isEmpty()) continue;
            if (this.world.random().nextInt(seen++) == 0) chosen = slot;
        }
        return chosen;
    }

    private boolean dropOne(int slot, Direction facing) {
        ItemStack one = this.inventory.extract(slot, 1);
        int tx = this.pos.x() + facing.offsetX();
        int ty = this.pos.y() + facing.offsetY();
        int tz = this.pos.z() + facing.offsetZ();
        BlockEntity targetEntity = this.world.getBlockEntity(tx, ty, tz);
        ItemStorage target = targetEntity == null ? null
                : targetEntity.getCapability(Capabilities.ITEM_STORAGE, facing.opposite()).orElse(null);
        if (target != null) {
            ItemStack rest = target.insert(one);
            if (rest.isEmpty()) {
                target.setChanged();
                return true;
            }
            this.restore(slot, rest);
            /* Vanilla wirft bei vorhandenem, aber vollem Ziel NICHT aus: Das Item bleibt liegen. */
            return false;
        }
        this.eject(one, facing);
        return true;
    }

    private boolean dispenseOne(int slot, Direction facing) {
        ItemStack stack = this.inventory.get(slot);
        Item item = stack.getItem();
        if (item instanceof BlockItem blockItem
                && blockItem.getBlock().getIdentifier().equals(Identifier.of("skyengine:tnt"))) {
            this.inventory.extract(slot, 1);
            ExplosionBehavior tnt = blockItem.getBlock().getBehavior(ExplosionBehavior.class);
            float power = tnt == null ? 4.0F : tnt.power();
            int fuse = tnt == null ? 80 : tnt.fuse();
            this.world.spawnPrimedTnt(
                    this.pos.x() + 0.5 + facing.offsetX() * 0.7,
                    this.pos.y() + 0.5 + facing.offsetY() * 0.7,
                    this.pos.z() + 0.5 + facing.offsetZ() * 0.7,
                    power, fuse);
            this.world.playDispenserSuccess(this.pos.x(), this.pos.y(), this.pos.z());
            return true;
        }
        if (item instanceof BucketItem bucket && this.dispenseBucket(slot, bucket, facing)) return true;
        if (item instanceof FlintAndSteelItem && this.useFlintAndSteel(slot, stack, facing)) return true;
        if (item instanceof ItemFrameItem) {
            int tx = this.pos.x() + facing.offsetX();
            int ty = this.pos.y() + facing.offsetY();
            int tz = this.pos.z() + facing.offsetZ();
            boolean placed = this.world.placeItemFrame(tx, ty, tz, facing);
            if (placed) {
                this.inventory.extract(slot, 1);
                this.world.playDispenserSuccess(this.pos.x(), this.pos.y(), this.pos.z());
            } else {
                this.world.playDispenserFailure(this.pos.x(), this.pos.y(), this.pos.z());
            }
            return placed;
        }
        if (item instanceof MinecartItem) {
            int tx = this.pos.x() + facing.offsetX();
            int ty = this.pos.y() + facing.offsetY();
            int tz = this.pos.z() + facing.offsetZ();
            BlockState rail = de.skyengine.game.world.block.behavior.RailBehavior.railAt(this.world, tx, ty, tz);
            if (rail == null && Blocks.getState(this.world.getBlock(tx, ty, tz)).isAir()) {
                rail = de.skyengine.game.world.block.behavior.RailBehavior.railAt(this.world, tx, ty - 1, tz);
                if (rail != null) ty--;
            }
            if (rail == null) {
                this.world.playDispenserFailure(this.pos.x(), this.pos.y(), this.pos.z());
                return false;
            }
            double offset = de.skyengine.game.world.block.behavior.RailBehavior.shape(rail).isAscending()
                    ? 0.5625 : 0.0625;
            this.world.spawnMinecart(tx + 0.5, ty + offset, tz + 0.5);
            this.inventory.extract(slot, 1);
            this.world.playDispenserSuccess(this.pos.x(), this.pos.y(), this.pos.z());
            return true;
        }

        this.eject(this.inventory.extract(slot, 1), facing);
        return true;
    }

    private boolean dispenseBucket(int slot, BucketItem bucket, Direction facing) {
        int tx = this.pos.x() + facing.offsetX();
        int ty = this.pos.y() + facing.offsetY();
        int tz = this.pos.z() + facing.offsetZ();
        if (!bucket.isEmpty()) {
            BlockState target = Blocks.getState(this.world.getBlock(tx, ty, tz));
            if (!(target.isAir() || target.isFluid() || target.getBlock().isReplaceable())) return false;
            if (!this.world.setBlock(tx, ty, tz, bucket.getFluid().getDefaultState().getId())) return false;
            this.replaceContainerItem(slot, Items.get(Identifier.of("skyengine:bucket")), facing);
            this.world.playDispenserSuccess(this.pos.x(), this.pos.y(), this.pos.z());
            return true;
        }

        BlockState target = Blocks.getState(this.world.getBlock(tx, ty, tz));
        if (!target.isFluid() || target.get(Properties.FALLING) || target.get(Properties.LEVEL) != 0) return false;
        Identifier filledId = Identifier.of("skyengine:" + target.getBlock().getIdentifier().path() + "_bucket");
        Item filled = Items.get(filledId);
        if (filled == null || !this.world.setBlock(tx, ty, tz, Blocks.AIR)) return false;
        this.replaceContainerItem(slot, filled, facing);
        this.world.playDispenserSuccess(this.pos.x(), this.pos.y(), this.pos.z());
        return true;
    }

    private void replaceContainerItem(int slot, Item replacement, Direction facing) {
        this.inventory.extract(slot, 1);
        ItemStack result = new ItemStack(replacement, 1);
        if (this.inventory.get(slot).isEmpty()) {
            this.inventory.set(slot, result);
            return;
        }
        ItemStack rest = this.inventory.insert(result);
        if (!rest.isEmpty()) this.eject(rest, facing);
    }

    /** In dieser Engine kann das Feuerzeug derzeit ausschließlich einen TNT-Block zünden. */
    private boolean useFlintAndSteel(int slot, ItemStack stack, Direction facing) {
        int tx = this.pos.x() + facing.offsetX();
        int ty = this.pos.y() + facing.offsetY();
        int tz = this.pos.z() + facing.offsetZ();
        BlockState target = Blocks.getState(this.world.getBlock(tx, ty, tz));
        ExplosionBehavior tnt = target.getBlock().getBehavior(ExplosionBehavior.class);
        if (tnt == null) return false;
        tnt.prime(this.world, tx, ty, tz);
        stack.setDamage(stack.getDamage() + 1);
        if (stack.getDamage() >= FlintAndSteelItem.DURABILITY) this.inventory.set(slot, ItemStack.EMPTY);
        else this.inventory.setChanged();
        this.world.playDispenserSuccess(this.pos.x(), this.pos.y(), this.pos.z());
        return true;
    }

    private void eject(ItemStack stack, Direction facing) {
        if (stack == null || stack.isEmpty()) return;
        ItemEntity entity = new ItemEntity(stack);
        double x = this.pos.x() + 0.5 + facing.offsetX() * 0.7;
        double y = this.pos.y() + 0.5 + facing.offsetY() * 0.7 - 0.3;
        double z = this.pos.z() + 0.5 + facing.offsetZ() * 0.7;
        entity.setPosition(x, y, z);
        double speed = this.world.random().nextDouble() * 0.1 + 0.2;
        entity.motionX = facing.offsetX() * speed + this.world.random().nextGaussian() * 0.0075 * 6;
        entity.motionY = 0.2 + facing.offsetY() * speed + this.world.random().nextGaussian() * 0.0075 * 6;
        entity.motionZ = facing.offsetZ() * speed + this.world.random().nextGaussian() * 0.0075 * 6;
        this.world.spawnEntity(entity);
        this.world.playDispenserSuccess(this.pos.x(), this.pos.y(), this.pos.z());
    }

    private void restore(int slot, ItemStack stack) {
        ItemStack current = this.inventory.get(slot);
        if (current.isEmpty()) this.inventory.set(slot, stack);
        else {
            current.setCount(current.getCount() + stack.getCount());
            this.inventory.setChanged();
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public <C> Optional<C> getCapability(Capability<C> capability, Direction side) {
        if (capability == Capabilities.ITEM_STORAGE) return Optional.of((C) this.inventory);
        return Optional.empty();
    }

    @Override
    public void save(DataTag tag) {
        DataTag inventoryTag = new DataTag();
        this.inventory.save(inventoryTag);
        tag.putTag("inventory", inventoryTag);
    }

    @Override
    public void load(DataTag tag) {
        this.inventory.load(tag.getTag("inventory"));
    }
}
