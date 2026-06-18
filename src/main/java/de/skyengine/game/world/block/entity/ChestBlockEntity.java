package de.skyengine.game.world.block.entity;

import de.skyengine.game.world.block.BlockPos;
import de.skyengine.game.world.block.Direction;

import java.util.Optional;

/**
 * Truhe: hält ein {@link SimpleItemStorage} (27 Slots), persistiert es über {@link DataTag}
 * und stellt es als {@link Capabilities#ITEM_STORAGE}-Capability bereit (für Hopper/Pipes/GUI).
 * Nicht tickend.
 */
public final class ChestBlockEntity extends BlockEntity {

    public static final int SLOTS = 27;

    private final SimpleItemStorage inventory = new SimpleItemStorage(SLOTS);

    public ChestBlockEntity(BlockEntityType<?> type, BlockPos pos) {
        super(type, pos);
    }

    public ItemStorage getInventory() {
        return this.inventory;
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
