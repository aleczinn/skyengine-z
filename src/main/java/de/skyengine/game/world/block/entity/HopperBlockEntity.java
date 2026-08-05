package de.skyengine.game.world.block.entity;

import de.skyengine.game.entity.ItemEntity;
import de.skyengine.game.physics.AABB;
import de.skyengine.game.world.block.BlockPos;
import de.skyengine.game.world.block.Blocks;
import de.skyengine.game.world.block.Direction;
import de.skyengine.game.world.block.state.BlockState;
import de.skyengine.game.world.block.state.Properties;
import de.skyengine.game.world.item.ItemStack;

import java.util.Optional;

/**
 * Trichter: 5-Slot-Inventar, schiebt Items in Blickrichtung ({@code facing}) in den
 * Nachbar-Container und zieht aus dem Container darüber nach — ohne Container darüber
 * saugt er dort liegende {@link ItemEntity}s ein. Transferrate aus der Block-JSON
 * ({@code hopper_cooldown}/{@code hopper_amount}, MC 8/1 = 2,5 Items/s); die schnelleren
 * Stufen (golden/diamond/netherite_hopper) sind reine Daten.
 *
 * <p>Ein Redstone-Signal DEAKTIVIERT den Trichter ({@code enabled=false} im State, wie MC) —
 * dann weder Push noch Pull noch Einsaugen. Der Cooldown zählt trotzdem weiter, damit ein
 * kurzer Puls den Takt nicht verschiebt.
 *
 * <p>Nach jedem Transfer: {@code markDirty} + {@code World.updateComparatorOutputs} für
 * Quelle UND Ziel — Container-Mutationen erzeugen keine Nachbar-Updates, das ist der
 * einzige Weg, auf dem ein messender Komparator davon erfährt.
 */
public final class HopperBlockEntity extends BlockEntity {

    public static final int SLOTS = 5;
    /** Vanillas Hopper.SUCK_AABB: volle Breite, von 11/16 im Hopper bis zwei Blöcke hoch. */
    private static final double SUCTION_MIN_Y = 11.0 / 16.0;

    private final SimpleItemStorage inventory = new SimpleItemStorage(SLOTS);
    /** Rest-Ticks bis zum nächsten Transferversuch (persistiert — der Takt überlebt Save/Load). */
    private int cooldown;
    /** Letzter eigener BE-Tick; Vanillas Phasenausgleich für Hopperketten. Nicht persistiert. */
    private long tickedGameTime;

    public HopperBlockEntity(BlockEntityType<?> type, BlockPos pos) {
        super(type, pos);
    }

    public ItemStorage getInventory() {
        return this.inventory;
    }

    /** Rest-Ticks bis zum nächsten Transfer (für den saveTest-Roundtrip: der Takt überlebt Save/Load). */
    public int getCooldown() {
        return this.cooldown;
    }

    @Override
    public void tick() {
        if (this.world == null) return;
        this.tickedGameTime = this.world.getGameTime();
        if (this.cooldown > 0) {
            this.cooldown--;
            if (this.cooldown > 0) return;
        }
        int x = this.pos.x(), y = this.pos.y(), z = this.pos.z();
        BlockState state = Blocks.getState(this.world.getBlock(x, y, z));
        /* Tolerante Validierung: der Block unter uns kann schon etwas anderes sein. */
        if (!state.getValues().containsKey(Properties.ENABLED)) return;
        if (!state.get(Properties.ENABLED)) return;

        int amount = state.getBlock().getHopperAmount();
        boolean pushed = this.pushOut(state.get(Properties.FACING_ALL), amount);
        boolean pulled = this.pullIn(amount);
        if (pushed || pulled) {
            this.cooldown = state.getBlock().getHopperCooldown();
            this.markDirty();
        }
    }

    /** Schiebt bis {@code amount} Items aus dem ersten belegten Slot in den Nachbarn Richtung facing. */
    private boolean pushOut(Direction facing, int amount) {
        int tx = this.pos.x() + facing.offsetX();
        int ty = this.pos.y() + facing.offsetY();
        int tz = this.pos.z() + facing.offsetZ();
        BlockEntity targetEntity = this.world.getBlockEntity(tx, ty, tz);
        ItemStorage target = storageOf(targetEntity, facing.opposite());
        if (target == null) return false;
        boolean targetWasEmpty = isEmpty(target);

        for (int i = 0; i < this.inventory.size(); i++) {
            if (this.inventory.get(i).isEmpty()) continue;
            ItemStack taken = this.inventory.extract(i, amount);
            ItemStack leftover = target.insert(taken);
            if (leftover.getCount() == taken.getCount()) {
                /* Ziel voll für dieses Item: zurückbuchen und den nächsten Slot probieren. */
                this.restore(i, leftover);
                continue;
            }
            if (!leftover.isEmpty()) this.restore(i, leftover);
            targetEntity.setChanged();
            if (targetWasEmpty && targetEntity instanceof HopperBlockEntity targetHopper) {
                targetHopper.receiveCooldownFrom(this);
            }
            this.world.updateComparatorOutputs(this.pos.x(), this.pos.y(), this.pos.z());
            this.world.updateComparatorOutputs(tx, ty, tz);
            return true;
        }
        return false;
    }

    /** Zieht aus dem Container über der Öffnung nach — ohne Container: ItemEntities einsaugen. */
    private boolean pullIn(int amount) {
        int x = this.pos.x(), y = this.pos.y(), z = this.pos.z();
        BlockEntity sourceEntity = this.world.getBlockEntity(x, y + 1, z);
        ItemStorage source = storageOf(sourceEntity, Direction.DOWN);
        if (source != null) {
            for (int i = 0; i < source.size(); i++) {
                if (source.get(i).isEmpty()) continue;
                ItemStack taken = source.extract(i, amount);
                ItemStack leftover = this.inventory.insert(taken);
                if (leftover.getCount() == taken.getCount()) {
                    restoreInto(source, i, leftover);
                    continue;
                }
                if (!leftover.isEmpty()) restoreInto(source, i, leftover);
                sourceEntity.setChanged();
                this.world.updateComparatorOutputs(x, y + 1, z);
                this.world.updateComparatorOutputs(x, y, z);
                return true;
            }
            return false;
        }
        BlockState above = Blocks.getState(this.world.getBlock(x, y + 1, z));
        if (above.getCollisionShape().isFullCube()) return false;
        return this.suckItems();
    }

    /**
     * Saugt {@link ItemEntity}s über der Öffnung ein (ganze Stapel, wie MC). Die Box reicht
     * einen Block über den Trichter — dort landen Items, die auf seiner Oberseite liegen.
     * Vertrag von {@code forEachEntityNearby}: nur removed-Flag setzen, Listen nicht anfassen.
     */
    private boolean suckItems() {
        int x = this.pos.x(), y = this.pos.y(), z = this.pos.z();
        AABB suction = new AABB(x, y + SUCTION_MIN_Y, z, x + 1, y + 2, z + 1);
        boolean[] moved = {false};
        this.world.forEachEntityNearby(x + 0.5, z + 0.5, 1, entity -> {
            if (moved[0]) return;   // ein Transfer pro Takt, wie Push/Pull
            if (!(entity instanceof ItemEntity item) || item.isRemoved()) return;
            if (!item.getBoundingBox().intersects(suction)) return;
            int before = item.getStack().getCount();
            ItemStack remaining = this.inventory.insert(item.getStack());
            if (remaining.isEmpty()) {
                item.remove();
            } else {
                item.getStack().setCount(remaining.getCount());
            }
            if (remaining.getCount() < before) {
                moved[0] = true;
                this.world.updateComparatorOutputs(x, y, z);
            }
        });
        return moved[0];
    }

    /** Bucht einen nicht untergebrachten Rest in den Slot zurück, aus dem er entnommen wurde. */
    private void restore(int slot, ItemStack leftover) {
        restoreInto(this.inventory, slot, leftover);
    }

    /* extract() hat den Rest im Slot gelassen (oder ihn geleert) — der Rückläufer ist
       garantiert dasselbe Item, Aufstocken ist also sicher. */
    private static void restoreInto(ItemStorage storage, int slot, ItemStack leftover) {
        ItemStack current = storage.get(slot);
        if (current.isEmpty()) {
            storage.set(slot, leftover);
        } else {
            current.setCount(current.getCount() + leftover.getCount());
        }
    }

    /** Item-Storage-Capability eines Nachbar-BlockEntities oder null. */
    private ItemStorage storageOf(BlockEntity entity, Direction side) {
        if (entity == null || entity == this) return null;
        return entity.getCapability(Capabilities.ITEM_STORAGE, side).orElse(null);
    }

    private static boolean isEmpty(ItemStorage storage) {
        for (int i = 0; i < storage.size(); i++) {
            if (!storage.get(i).isEmpty()) return false;
        }
        return true;
    }

    /**
     * Vanillas {@code tryMoveInItem}: Ein leerer Zielhopper erhält 8 Ticks Cooldown, oder 7,
     * wenn er in diesem Spieltick bereits vor bzw. gleichzeitig mit der Quelle getickt hat.
     */
    private void receiveCooldownFrom(HopperBlockEntity source) {
        if (this.cooldown > 8) return;
        int phaseOffset = this.tickedGameTime >= source.tickedGameTime ? 1 : 0;
        this.cooldown = 8 - phaseOffset;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <C> Optional<C> getCapability(Capability<C> capability, Direction side) {
        if (capability == Capabilities.ITEM_STORAGE) {
            return Optional.of((C) this.inventory);
        }
        return Optional.empty();
    }

    @Override
    public void save(DataTag tag) {
        DataTag inv = new DataTag();
        this.inventory.save(inv);
        tag.putTag("inventory", inv);
        tag.putInt("cooldown", this.cooldown);
    }

    @Override
    public void load(DataTag tag) {
        this.inventory.load(tag.getTag("inventory"));
        this.cooldown = tag.getInt("cooldown", 0);
    }
}
