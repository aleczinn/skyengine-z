package de.skyengine.game.world.block.archetype;

import de.skyengine.audio.BlockOpenSound;
import de.skyengine.audio.BlockSoundGroup;
import de.skyengine.game.world.block.Block;
import de.skyengine.game.world.block.Direction;
import de.skyengine.game.world.block.Identifier;
import de.skyengine.game.world.block.Tints;
import de.skyengine.game.world.block.behavior.ExplosionBehavior;
import de.skyengine.game.world.block.behavior.GravityBehavior;
import de.skyengine.game.world.block.behavior.HorizontalFacingBehavior;
import de.skyengine.game.world.block.behavior.PartsBehavior;
import de.skyengine.game.world.block.behavior.SupportBehavior;
import de.skyengine.game.world.block.connection.ConnectionBehavior;
import de.skyengine.game.world.block.connection.ConnectionComponent;
import de.skyengine.game.world.block.connection.ConnectionRule;
import de.skyengine.game.world.block.connection.ConnectionRules;
import de.skyengine.game.world.block.entity.BlockEntityType;
import de.skyengine.game.world.block.entity.Capabilities;
import de.skyengine.game.world.block.json.BlockDefinition;
import de.skyengine.game.world.block.registry.Registries;
import de.skyengine.game.world.block.state.JsonProperties;
import de.skyengine.game.world.block.state.Properties;
import de.skyengine.game.world.block.state.Property;
import de.skyengine.game.world.item.ToolTier;
import de.skyengine.game.world.item.ToolType;
import de.skyengine.utils.logging.LogManager;
import de.skyengine.utils.logging.Logger;

import java.util.List;

/** Baut aus einem {@link Archetype} + {@link BlockDefinition} einen fertig konfigurierten Block. */
public final class ArchetypeBlockFactory {

    private static final Logger LOGGER = LogManager.getLogger(ArchetypeBlockFactory.class.getName());

    public static Block create(Archetype archetype, Identifier id, Block.Settings settings, BlockDefinition def) {
        BlockConfig.Builder builder = BlockConfig.builder();
        archetype.configure(builder, def);

        /* Optionaler BlockEntity-Typ aus der JSON — archetypübergreifend. */
        if (def.block_entity != null) {
            BlockEntityType<?> type = Registries.BLOCK_ENTITY.get(Identifier.of(def.block_entity));
            if (type != null) builder.blockEntity(type);
        }

        /* Selbst deklarierte Properties. Bewusst NACH archetype.configure: so stehen sie hinter
           den Archetyp-Properties und die State-Reihenfolge bestehender Blöcke bleibt gleich. */
        if (def.properties != null) {
            applyJsonProperties(builder, def);
        }

        /* Mehrteilige Blöcke (Tür, hohe Pflanze, Bett) rein aus der JSON. */
        if (def.parts != null) {
            PartsBehavior parts = PartsBehavior.of(def.parts, def.id);
            if (parts != null) builder.behavior(parts);
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

        /* Explosion (TNT) - archetypübergreifendes Flag, hängt das ExplosionBehavior mit Sprengkraft an. */
        if (def.explosion_power != null && def.explosion_power > 0) {
            int fuse = def.explosion_fuse != null ? def.explosion_fuse : 80;
            builder.behavior(new ExplosionBehavior(def.explosion_power, fuse));
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
        /* Explosions-Widerstand: ohne eigenes Feld gilt die Härte (MC-Semantik). Die Auflösung
           passiert HIER und nicht im BlockConfig-Default, damit Bedrock (hardness -1) den Wert
           -1 erbt und damit unzerstörbar bleibt, ohne ihn doppelt in der JSON zu führen. */
        builder.resistance(def.resistance != null ? def.resistance
                : (def.hardness != null ? def.hardness : 0F));

        /* Bewegung: Reibung, Tempo- und Sprungfaktor (Eis, Seelensand, Honig). */
        if (def.friction != null) {
            builder.friction(def.friction);
        }
        if (def.speed_factor != null) {
            builder.speedFactor(def.speed_factor);
        }
        if (def.jump_factor != null) {
            builder.jumpFactor(def.jump_factor);
        }
        if (def.bounciness != null) {
            builder.bounciness(def.bounciness);
        }
        if (def.fall_damage_factor != null) {
            builder.fallDamageFactor(def.fall_damage_factor);
        }

        /* Licht-Opazität: ohne Angabe entscheidet Block.getLightOpacity automatisch per State. */
        if (def.light_opacity != null) {
            builder.lightOpacity(Math.clamp(def.light_opacity.intValue(), 0, 15));
        }

        /* Eigenleuchten. Die Farbe wird nur geparst und abgelegt — sie wirkt noch nicht. */
        if (def.light_level != null) {
            builder.lightLevel(Math.clamp(def.light_level.intValue(), 0, 15));
        }
        if (def.light_color != null) {
            builder.lightColor(parseHexColor(def.light_color, id));
        }

        /* Sound-Gruppe: explizites JSON-Feld oder Ableitung aus Tool/Archetyp. */
        String archetypeName = def.archetype != null ? def.archetype : def.type;
        builder.sound(BlockSoundGroup.resolve(def.sound, ToolType.byName(def.tool), archetypeName));
        /* Auf-/Zu-Sound (Tür, Truhe) — eigenes Konzept, null für alles andere. */
        builder.openSound(BlockOpenSound.resolve(def.open_sound, archetypeName));

        builder.replaceable(def.replaceable);

        return new Block(id, settings, builder.build());
    }

    /**
     * "#RRGGBB" (das {@code #} ist optional) -> 0xRRGGBB. Eine unbrauchbare Angabe ist kein
     * Abbruchgrund — der Block leuchtet dann eben weiß —, wird aber gemeldet: {@code saveTest}
     * liest die Warnungen mit.
     */
    private static int parseHexColor(String value, Identifier id) {
        String hex = value.startsWith("#") ? value.substring(1) : value;
        if (hex.length() == 6) {
            try {
                return Integer.parseInt(hex, 16);
            } catch (NumberFormatException ignored) {
                /* fällt unten auf Weiß zurück */
            }
        }
        LOGGER.warning("light_color '" + value + "' von " + id + " ist kein #RRGGBB - nehme Weiss");
        return 0xFFFFFF;
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

    /** Hängt die {@code properties}-Sektion an; ungültige Einträge werden von JsonProperties gemeldet. */
    private static void applyJsonProperties(BlockConfig.Builder builder, BlockDefinition def) {
        for (var entry : def.properties.entrySet()) {
            BlockDefinition.PropertyDef p = entry.getValue();
            if (p == null || p.values == null) {
                LOGGER.error("Property '" + entry.getKey() + "' in " + def.id + " hat kein 'values'");
                continue;
            }
            List<String> values = List.of(p.values);
            Property<String> property = JsonProperties.of(entry.getKey(), values);
            if (property == null) continue;   // Grund steht bereits im Log

            builder.property(property);
            if (p.defaultValue != null) {
                if (values.contains(p.defaultValue)) {
                    builder.defaultValue(property, p.defaultValue);
                } else {
                    LOGGER.error("Default '" + p.defaultValue + "' ist kein Wert von '"
                            + entry.getKey() + "' in " + def.id);
                }
            }
        }
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
