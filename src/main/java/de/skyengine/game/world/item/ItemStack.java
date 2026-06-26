package de.skyengine.game.world.item;

import de.skyengine.game.world.block.Identifier;
import de.skyengine.game.world.block.entity.DataTag;

/**
 * Ein Stapel: {@link Item} + Anzahl. {@link #EMPTY} repräsentiert „nichts" (leerer Slot).
 * Bewusst schlicht (kein NBT pro Stack) - reicht für Truhe/Ofen; erweiterbar.
 */
public final class ItemStack {

    public static final ItemStack EMPTY = new ItemStack(null, 0);

    private final Item item;
    private int count;

    public ItemStack(Item item, int count) {
        this.item = item;
        this.count = item == null ? 0 : Math.max(0, count);
    }

    public boolean isEmpty() {
        return this.item == null || this.count <= 0;
    }

    public Item getItem() {
        return item;
    }

    public int getCount() {
        return count;
    }

    public void setCount(int count) {
        this.count = Math.max(0, count);
    }

    public int getMaxStackSize() {
        return this.item == null ? 0 : this.item.getMaxStackSize();
    }

    /** true, wenn beide denselben Item-Typ tragen (stapelbar). */
    public boolean canStackWith(ItemStack other) {
        return !this.isEmpty() && !other.isEmpty() && this.item == other.item;
    }

    public ItemStack copy() {
        return this.isEmpty() ? EMPTY : new ItemStack(this.item, this.count);
    }

    /* --- Persistenz (DataTag) --- */

    public DataTag save() {
        DataTag tag = new DataTag();
        if (!this.isEmpty()) {
            tag.putString("id", this.item.getId().toString());
            tag.putInt("count", this.count);
        }
        return tag;
    }

    public static ItemStack load(DataTag tag) {
        if (tag == null) return EMPTY;
        String id = tag.getString("id", null);
        if (id == null) return EMPTY;
        Item item = Items.get(Identifier.of(id));
        return item == null ? EMPTY : new ItemStack(item, tag.getInt("count", 1));
    }

    @Override
    public String toString() {
        return this.isEmpty() ? "EMPTY" : this.count + "x " + this.item;
    }
}
