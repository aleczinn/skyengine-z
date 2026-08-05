package de.skyengine.game.world.block.entity;

import de.skyengine.game.world.block.Identifier;
import de.skyengine.game.world.block.network.CableBlockEntity;
import de.skyengine.game.world.block.registry.Registries;

/**
 * Registriert die mitgelieferten BlockEntity-Typen. Muss vor dem Laden der Block-JSONs laufen,
 * damit ein {@code block_entity}-Verweis aufgelöst werden kann.
 */
public final class BlockEntities {

    public static BlockEntityType<CableBlockEntity> ENERGY_CABLE;
    public static BlockEntityType<ChestBlockEntity> CHEST;
    public static BlockEntityType<EnchantingTableBlockEntity> ENCHANTING_TABLE;
    public static BlockEntityType<PistonMovingBlockEntity> PISTON_MOVING;
    public static BlockEntityType<HopperBlockEntity> HOPPER;

    private static boolean registered;

    public static void bootstrap() {
        if (registered) return;
        registered = true;

        ENERGY_CABLE = Registries.BLOCK_ENTITY.register(Identifier.of("energy_cable"),
                new BlockEntityType<>((type, pos, state) -> new CableBlockEntity(type, pos), true));

        CHEST = Registries.BLOCK_ENTITY.register(Identifier.of("chest"),
                new BlockEntityType<>((type, pos, state) -> new ChestBlockEntity(type, pos), true));

        ENCHANTING_TABLE = Registries.BLOCK_ENTITY.register(Identifier.of("enchanting_table"),
                new BlockEntityType<>((type, pos, state) -> new EnchantingTableBlockEntity(type, pos), true));

        PISTON_MOVING = Registries.BLOCK_ENTITY.register(Identifier.of("piston_moving"),
                new BlockEntityType<>((type, pos, state) -> new PistonMovingBlockEntity(type, pos), true));

        HOPPER = Registries.BLOCK_ENTITY.register(Identifier.of("hopper"),
                new BlockEntityType<>((type, pos, state) -> new HopperBlockEntity(type, pos), true));
    }

    private BlockEntities() {}
}
