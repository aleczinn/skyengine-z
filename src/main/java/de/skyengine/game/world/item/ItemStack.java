package de.skyengine.game.world.item;

import de.skyengine.game.world.block.Identifier;
import de.skyengine.game.world.block.entity.DataTag;
import de.skyengine.graphics.gui.text.RichText;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

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
    /** Nur bei verzauberten Stacks alloziert; Schlüssel sind stabile Registry-IDs. */
    private Map<Identifier, Integer> enchantments;

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

    /** Formatierter Anzeigename; leer bei einem leeren Stack. */
    public RichText getDisplayNameText() {
        return this.isEmpty() ? RichText.EMPTY
                : this.item.getDisplayNameText();
    }

    /** true, wenn beide denselben Item-Typ tragen (stapelbar). */
    public boolean canStackWith(ItemStack other) {
        return !this.isEmpty() && !other.isEmpty() && this.item == other.item
                && this.damage == other.damage && Objects.equals(this.enchantments, other.enchantments);
    }

    public int getDamage() {
        return damage;
    }

    public void setDamage(int damage) {
        this.damage = Math.max(0, damage);
    }

    public int getEnchantmentLevel(Enchantment enchantment) {
        return this.enchantments == null ? 0 : this.enchantments.getOrDefault(enchantment.id(), 0);
    }

    public int getEnchantmentLevel(Identifier id) {
        Enchantment enchantment = Enchantments.get(id);
        return enchantment == null ? 0 : getEnchantmentLevel(enchantment);
    }

    public void setEnchantment(Enchantment enchantment, int level) {
        if (this.isEmpty()) return;
        int clamped = Math.max(0, Math.min(level, enchantment.maxLevel()));
        if (clamped == 0) {
            if (this.enchantments != null) {
                this.enchantments.remove(enchantment.id());
                if (this.enchantments.isEmpty()) this.enchantments = null;
            }
            return;
        }
        if (this.enchantments == null) this.enchantments = new LinkedHashMap<>(2);
        this.enchantments.put(enchantment.id(), clamped);
    }

    public ItemStack copy() {
        if (this.isEmpty()) return EMPTY;
        ItemStack copy = new ItemStack(this.item, this.count);
        copy.damage = this.damage;
        if (this.enchantments != null) copy.enchantments = new LinkedHashMap<>(this.enchantments);
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
            if (this.enchantments != null) {
                DataTag enchantmentTag = new DataTag();
                for (Map.Entry<Identifier, Integer> entry : this.enchantments.entrySet()) {
                    enchantmentTag.putInt(entry.getKey().toString(), entry.getValue());
                }
                tag.putTag("enchantments", enchantmentTag);
            }
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
        DataTag enchantmentTag = tag.getTag("enchantments");
        if (enchantmentTag != null) {
            for (Map.Entry<String, Object> entry : enchantmentTag.raw().entrySet()) {
                if (!(entry.getValue() instanceof Number number)) continue;
                Enchantment enchantment = Enchantments.get(Identifier.of(entry.getKey()));
                if (enchantment != null) stack.setEnchantment(enchantment, number.intValue());
            }
        }
        return stack;
    }

    @Override
    public String toString() {
        return this.isEmpty() ? "EMPTY" : this.count + "x " + this.item;
    }
}
