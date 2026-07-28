package de.skyengine.game.world.block.entity;

import de.skyengine.audio.BlockOpenSound;
import de.skyengine.audio.SoundManager;
import de.skyengine.game.world.block.BlockPos;
import de.skyengine.game.world.block.Blocks;
import de.skyengine.game.world.block.Direction;
import de.skyengine.game.world.block.state.BlockState;
import de.skyengine.game.world.block.state.ChestType;
import de.skyengine.game.world.block.state.Properties;

import java.util.Optional;

/**
 * Truhe: hält ein {@link SimpleItemStorage} (27 Slots), persistiert es über {@link DataTag}
 * und stellt es als {@link Capabilities#ITEM_STORAGE}-Capability bereit (für Hopper/Pipes/GUI).
 * Nicht tickend.
 */
public final class ChestBlockEntity extends BlockEntity {

    public static final int SLOTS = 27;

    private final SimpleItemStorage inventory = new SimpleItemStorage(SLOTS);

    /* Deckel-Animation: Ziel-Zustand + interpolierter Öffnungsgrad (0=zu, 1=offen). */
    private static final float OPEN_STEP = 0.15f;
    private boolean open;
    private float openness;
    private float lastOpenness;

    public ChestBlockEntity(BlockEntityType<?> type, BlockPos pos) {
        super(type, pos);
    }

    public ItemStorage getInventory() {
        return this.inventory;
    }

    /** Rückt den Öffnungsgrad pro Tick Richtung Ziel (für die Deckel-Animation). */
    @Override
    public void tick() {
        this.lastOpenness = this.openness;
        float target = this.open ? 1f : 0f;
        if (this.openness < target) this.openness = Math.min(target, this.openness + OPEN_STEP);
        else if (this.openness > target) this.openness = Math.max(target, this.openness - OPEN_STEP);
    }

    public void toggle() {
        this.setOpen(!this.open);
    }

    /**
     * Setzt den Deckel-Zielzustand (z.B. beim Öffnen/Schließen des Truhen-GUI) und spielt den
     * Auf-/Zu-Sound. Der Hook sitzt bewusst HIER und nicht in {@code GuiChest.onClose}: das GUI
     * lässt sich auf mehreren Wegen schließen (ESC, Inventar-Taste, Todesscreen, Welt verlassen),
     * die alle hier durchlaufen — und so kann der Sound nicht von der Deckel-Animation abdriften.
     */
    public void setOpen(boolean open) {
        this.setOpen(open, true);
    }

    /**
     * Wie {@link #setOpen(boolean)}, aber optional stumm — die zweite Hälfte einer Doppeltruhe
     * bewegt ihren Deckel mit, darf aber nicht denselben Sound ein zweites Mal spielen.
     */
    public void setOpen(boolean open, boolean sound) {
        if (open == this.open) return;   // nur echte Wechsel klingen
        this.open = open;
        if (sound) this.playToggleSound(open);
    }

    /** Auf-/Zu-Sound aus der Block-Definition; stumm ohne Welt, SoundManager oder Sound-Satz. */
    private void playToggleSound(boolean open) {
        if (this.world == null) return;
        SoundManager sound = this.world.getSoundManager();
        if (sound == null) return;
        BlockOpenSound set = Blocks.getState(this.world.getBlock(this.pos.x(), this.pos.y(), this.pos.z()))
                .getBlock().getOpenSound();
        if (set == null) return;
        double x = this.pos.x() + 0.5, y = this.pos.y() + 0.5, z = this.pos.z() + 0.5;
        if (open) sound.playBlockOpen(set, x, y, z);
        else sound.playBlockClose(set, x, y, z);
    }

    /** Interpolierter Öffnungsgrad [0..1] für flüssige Animation zwischen Ticks. */
    public float getOpenness(float partialTick) {
        return this.lastOpenness + (this.openness - this.lastOpenness) * partialTick;
    }

    /**
     * Horizontale Ausrichtung der Truhe aus dem BlockState (für den Renderer). Default SOUTH
     * (= kanonische Modellfront +Z, keine Drehung), falls noch keine Welt gesetzt ist.
     */
    public Direction getFacing() {
        if (this.world == null) return Direction.SOUTH;
        BlockState state = Blocks.getState(this.world.getBlock(this.pos.x(), this.pos.y(), this.pos.z()));
        if (state.getValues().containsKey(Properties.FACING)) {
            return state.get(Properties.FACING);
        }
        return Direction.SOUTH;
    }

    /**
     * Rolle in einer Doppeltruhe aus dem BlockState (für den Renderer). Ohne Welt oder Property
     * eine Einzeltruhe — dasselbe Fallback-Muster wie {@link #getFacing()}.
     */
    public ChestType getChestType() {
        if (this.world == null) return ChestType.SINGLE;
        BlockState state = Blocks.getState(this.world.getBlock(this.pos.x(), this.pos.y(), this.pos.z()));
        if (state.getValues().containsKey(Properties.CHEST_TYPE)) {
            return state.get(Properties.CHEST_TYPE);
        }
        return ChestType.SINGLE;
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
    }

    @Override
    public void load(DataTag tag) {
        this.inventory.load(tag.getTag("inventory"));
    }
}
