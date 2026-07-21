package de.skyengine.game.world.block.archetype;

import de.skyengine.audio.BlockSoundGroup;
import de.skyengine.game.world.block.Block;
import de.skyengine.game.world.block.Direction;
import de.skyengine.game.world.block.Identifier;
import de.skyengine.game.world.block.Tints;
import de.skyengine.game.world.block.behavior.GravityBehavior;
import de.skyengine.game.world.block.behavior.HorizontalFacingBehavior;
import de.skyengine.game.world.block.behavior.SupportBehavior;
import de.skyengine.game.world.block.connection.ConnectionBehavior;
import de.skyengine.game.world.block.connection.ConnectionComponent;
import de.skyengine.game.world.block.connection.ConnectionRule;
import de.skyengine.game.world.block.connection.ConnectionRules;
import de.skyengine.game.world.block.entity.BlockEntityType;
import de.skyengine.game.world.block.entity.Capabilities;
import de.skyengine.game.world.block.json.BlockDefinition;
import de.skyengine.game.world.block.registry.Registries;
import de.skyengine.game.world.block.state.Properties;
import de.skyengine.game.world.item.ToolTier;
import de.skyengine.game.world.item.ToolType;

import java.util.List;

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

        /* Horizontale Ausrichtung (Truhe, Ofen) - FACING-Property + Platzier-Verhalten zum Spieler. */
        if (def.facing) {
            builder.property(Properties.FACING);
            builder.behavior(new HorizontalFacingBehavior());
        }

        /* Vegetations-Tint (Gras, Farn, Laub) - archetypübergreifend aus der JSON. */
        if (def.tint != null) {
            builder.tint(Tints.byName(def.tint));
            builder.tintType(Tints.typeByName(def.tint));
            builder.tintFaces(parseFaceMask(def.tint_faces));
        }
        String overlay = def.textures.get("overlay");
        if (overlay != null) {
            builder.overlayTexture(overlay);
        }

        /* Stütz-/Platzierungsregeln (Cactus nur auf Sand, Tür nur auf voller Oberseite). */
        if (def.place_on != null || def.place_on_full_top) {
            List<String> placeOn = def.place_on == null ? null : List.of(def.place_on);
            builder.placeOn(placeOn);
            builder.placeOnFullTop(def.place_on_full_top);
            builder.behavior(new SupportBehavior(placeOn, def.place_on_full_top));
        }

        /* Survival-Mining: Härte + effektive Tool-Klasse + Mindest-Tier für Drops. */
        if (def.hardness != null) {
            builder.hardness(def.hardness);
        }
        if (def.tool != null) {
            builder.toolType(ToolType.byName(def.tool));
        }
        if (def.harvest_tier != null) {
            builder.harvestLevel(ToolTier.levelByName(def.harvest_tier));
        }

        /* Sound-Gruppe: explizites JSON-Feld oder Ableitung aus Tool/Archetyp. */
        String archetypeName = def.archetype != null ? def.archetype : def.type;
        builder.sound(BlockSoundGroup.resolve(def.sound, ToolType.byName(def.tool), archetypeName));

        builder.replaceable(def.replaceable);

        return new Block(id, settings, builder.build());
    }

    /** up/down/... -> Bitmaske (1 << Face-Index); null/leer = alle Quads (-1). */
    private static int parseFaceMask(String[] faces) {
        if (faces == null || faces.length == 0) return -1;
        int mask = 0;
        for (String face : faces) {
            mask |= 1 << switch (face.toLowerCase()) {
                case "up" -> 0;
                case "down" -> 1;
                case "north" -> 2;
                case "south" -> 3;
                case "west" -> 4;
                case "east" -> 5;
                default -> throw new IllegalArgumentException("Unbekanntes tint_face: " + face);
            };
        }
        return mask;
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
