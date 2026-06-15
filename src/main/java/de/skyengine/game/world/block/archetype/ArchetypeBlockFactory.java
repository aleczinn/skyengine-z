package de.skyengine.game.world.block.archetype;

import de.skyengine.game.world.block.Block;
import de.skyengine.game.world.block.Identifier;
import de.skyengine.game.world.block.entity.BlockEntityType;
import de.skyengine.game.world.block.json.BlockDefinition;
import de.skyengine.game.world.block.registry.Registries;

/** Baut aus einem {@link Archetype} + {@link BlockDefinition} einen fertig konfigurierten Block. */
public final class ArchetypeBlockFactory {

    public static Block create(Archetype archetype, Identifier id, Block.Settings settings, BlockDefinition def) {
        BlockConfig.Builder builder = BlockConfig.builder();
        archetype.configure(builder, def);

        /* Optionaler BlockEntity-Typ aus der JSON — archetypübergreifend. */
        if (def.block_entity != null) {
            BlockEntityType<?> type = Registries.BLOCK_ENTITY.get(Identifier.of(def.block_entity));
            if (type != null) builder.blockEntity(type);
        }
        return new Block(id, settings, builder.build());
    }

    private ArchetypeBlockFactory() {}
}
