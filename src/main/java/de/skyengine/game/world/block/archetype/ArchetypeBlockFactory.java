package de.skyengine.game.world.block.archetype;

import de.skyengine.game.world.block.Block;
import de.skyengine.game.world.block.Direction;
import de.skyengine.game.world.block.Identifier;
import de.skyengine.game.world.block.behavior.GravityBehavior;
import de.skyengine.game.world.block.connection.ConnectionBehavior;
import de.skyengine.game.world.block.connection.ConnectionComponent;
import de.skyengine.game.world.block.connection.ConnectionRule;
import de.skyengine.game.world.block.connection.ConnectionRules;
import de.skyengine.game.world.block.entity.BlockEntityType;
import de.skyengine.game.world.block.entity.Capabilities;
import de.skyengine.game.world.block.json.BlockDefinition;
import de.skyengine.game.world.block.registry.Registries;
import de.skyengine.game.world.block.state.Properties;

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

        /* Generisches Connection-System aus JSON (Pipes/Cables ohne eigenen Archetyp). */
        if (def.connection != null) {
            applyConnection(builder, def.connection);
        }

        /* Kollisions-Override (getrennt vom Modell) ersetzt die Archetyp-Default-Shape. */
        if (def.collision != null) {
            builder.shapes(de.skyengine.game.world.block.shape.JsonShapeProvider.of(def.collision));
        }

        /* Schwerkraft (Sand, Kies) - archetypübergreifendes Flag, hängt das GravityBehavior an. */
        if (def.gravity) {
            builder.behavior(new GravityBehavior());
        }
        return new Block(id, settings, builder.build());
    }

    private static void applyConnection(BlockConfig.Builder builder, BlockDefinition.ConnectionDef def) {
        Direction[] axes = parseAxes(def.axes);
        ConnectionRule rule = "energy".equalsIgnoreCase(def.rule)
                ? ConnectionRules.networkOrCapability(Capabilities.ENERGY)
                : ConnectionRules.SAME_GROUP_OR_SOLID;

        for (Direction d : axes) builder.property(Properties.connection(d));
        builder.behavior(new ConnectionBehavior(new ConnectionComponent(axes, rule)));
        if (def.group != null) builder.connectionGroup(def.group);
    }

    private static Direction[] parseAxes(String[] names) {
        if (names == null || names.length == 0) return Direction.horizontal();
        Direction[] out = new Direction[names.length];
        for (int i = 0; i < names.length; i++) out[i] = Direction.valueOf(names[i].toUpperCase());
        return out;
    }

    private ArchetypeBlockFactory() {}
}
