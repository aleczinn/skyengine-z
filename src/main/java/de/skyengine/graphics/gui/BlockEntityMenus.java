package de.skyengine.graphics.gui;

import de.skyengine.game.world.block.entity.BlockEntity;
import de.skyengine.game.world.block.entity.BlockEntityType;
import de.skyengine.game.world.block.entity.ItemStorage;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.function.BiFunction;

/** Extensible menu factory registry for block entities. */
public final class BlockEntityMenus {
    private static final Map<BlockEntityType<?>, BiFunction<BlockEntity, ItemStorage, GuiScreen>> FACTORIES =
            new IdentityHashMap<>();

    @SuppressWarnings("unchecked")
    public static <T extends BlockEntity> void register(BlockEntityType<T> type,
            BiFunction<T, ItemStorage, GuiScreen> factory) {
        FACTORIES.put(type, (entity, inventory) -> factory.apply((T) entity, inventory));
    }

    public static GuiScreen create(BlockEntity entity, ItemStorage playerInventory) {
        if (entity == null) return null;
        BiFunction<BlockEntity, ItemStorage, GuiScreen> factory = FACTORIES.get(entity.getType());
        return factory == null ? null : factory.apply(entity, playerInventory);
    }

    public static void clear() { FACTORIES.clear(); }
    private BlockEntityMenus() {}
}
