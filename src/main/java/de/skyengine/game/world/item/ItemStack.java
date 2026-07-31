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
    /** Abnutzung (Tools): 0 = neu; erreicht sie die Tier-Haltbarkeit, zerbricht das Tool. */
    private int damage;

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

    /** Übersetzter Anzeigename des Items (leer bei leerem Stack). */
    public String getDisplayName() {
        return this.isEmpty() ? "" : this.item.getDisplayName();
    }

    /** true, wenn beide denselben Item-Typ tragen (stapelbar). */
    public boolean canStackWith(ItemStack other) {
        return !this.isEmpty() && !other.isEmpty() && this.item == other.item;
    }

    public int getDamage() {
        return damage;
    }

    public void setDamage(int damage) {
        this.damage = Math.max(0, damage);
    }

    public ItemStack copy() {
        if (this.isEmpty()) return EMPTY;
        ItemStack copy = new ItemStack(this.item, this.count);
        copy.damage = this.damage;
        return copy;
    }

    /**
     * Nimmt bis zu {@code amount} von diesem Stapel ab und liefert sie als eigenen Stapel
     * (Vorbild MC {@code ItemStack.split}). Über {@link #copy()} bleibt die Abnutzung erhalten.
     * Der {@link #isEmpty()}-Guard schützt zugleich das geteilte {@link #EMPTY} vor Mutation.
     */
    public ItemStack split(int amount) {
        if (this.isEmpty() || amount <= 0) return EMPTY;
        ItemStack out = this.copy();
        out.setCount(Math.min(amount, this.count));
        this.count -= out.getCount();
        return out;
    }

    /* --- Persistenz (DataTag) --- */

    public DataTag save() {
        DataTag tag = new DataTag();
        if (!this.isEmpty()) {
            tag.putString("id", this.item.getId().toString());
            tag.putInt("count", this.count);
            if (this.damage > 0) tag.putInt("damage", this.damage);
        }
        return tag;
    }

    public static ItemStack load(DataTag tag) {
        if (tag == null) return EMPTY;
        String id = tag.getString("id", null);
        if (id == null) return EMPTY;
        Item item = Items.get(Identifier.of(id));
        if (item == null) return EMPTY;
        ItemStack stack = new ItemStack(item, tag.getInt("count", 1));
        stack.damage = tag.getInt("damage", 0);
        return stack;
    }

    @Override
    public String toString() {
        return this.isEmpty() ? "EMPTY" : this.count + "x " + this.item;
    }
}
