package de.skyengine.game.command;

import de.skyengine.core.i18n.I18n;
import de.skyengine.game.world.block.Block;
import de.skyengine.game.world.block.Identifier;
import de.skyengine.game.world.block.registry.Registries;
import de.skyengine.game.world.block.state.BlockState;
import de.skyengine.game.world.block.state.BlockStateCodec;
import de.skyengine.game.world.block.state.Property;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Strikter Command-Parser; anders als der Save-Codec ignoriert er keine unbekannten Properties. */
final class WorldEditBlockParser {
    record Matcher(Block block, Map<Property<?>, Object> properties) {
        boolean matches(int stateId) {
            BlockState state = de.skyengine.game.world.block.Blocks.getState(stateId);
            if (state.getBlock() != block) return false;
            for (Map.Entry<Property<?>, Object> entry : properties.entrySet()) {
                if (!entry.getValue().equals(state.getValues().get(entry.getKey()))) return false;
            }
            return true;
        }
    }

    static BlockState parse(String input) {
        Parsed parsed = parseInput(input);
        BlockState state = parsed.block().getDefaultState();
        for (Map.Entry<Property<?>, String> entry : parsed.properties().entrySet()) {
            state = apply(state, entry.getKey(), entry.getValue());
        }
        return state;
    }

    static Matcher parseMatcher(String input) {
        Parsed parsed = parseInput(input);
        BlockState defaults = parsed.block().getDefaultState();
        Map<Property<?>, Object> values = new LinkedHashMap<>();
        for (Map.Entry<Property<?>, String> entry : parsed.properties().entrySet()) {
            Object value = parseValue(entry.getKey(), entry.getValue());
            values.put(entry.getKey(), value);
        }
        return new Matcher(defaults.getBlock(), Map.copyOf(values));
    }

    private static Parsed parseInput(String input) {
        String value = input == null ? "" : input.trim().toLowerCase(Locale.ROOT);
        if (value.isEmpty()) throw new IllegalArgumentException(I18n.tr("command.worldedit.block_usage"));
        int open = value.indexOf('[');
        int close = value.lastIndexOf(']');
        if ((open < 0) != (close < 0) || close >= 0 && close != value.length() - 1
                || open >= 0 && value.indexOf('[', open + 1) >= 0 || close >= 0 && close < open) {
            throw new IllegalArgumentException(I18n.tr("command.worldedit.invalid_state_syntax", input));
        }
        String idPart = open < 0 ? value : value.substring(0, open);
        Block block = Registries.BLOCK.get(Identifier.of(idPart));
        if (block == null) throw new IllegalArgumentException(I18n.tr("command.worldedit.unknown_block", idPart));
        if (open < 0) return new Parsed(block, Map.of());

        String inner = value.substring(open + 1, close);
        if (inner.isEmpty()) return new Parsed(block, Map.of());
        Set<String> seen = new HashSet<>();
        Map<Property<?>, String> properties = new LinkedHashMap<>();
        for (String pair : inner.split(",", -1)) {
            int equals = pair.indexOf('=');
            if (equals <= 0 || equals != pair.lastIndexOf('=') || equals == pair.length() - 1) {
                throw new IllegalArgumentException(I18n.tr("command.worldedit.invalid_property", pair));
            }
            String name = pair.substring(0, equals), propertyValue = pair.substring(equals + 1);
            if (!seen.add(name)) throw new IllegalArgumentException(
                    I18n.tr("command.worldedit.duplicate_property", name));
            Property<?> property = block.getDefaultState().getValues().keySet().stream()
                    .filter(candidate -> candidate.getName().equals(name)).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException(
                            I18n.tr("command.worldedit.unknown_property", name)));
            /* Schon hier validieren, der Matcher behaelt aber nur explizit genannte Werte. */
            parseValue(property, propertyValue);
            properties.put(property, propertyValue);
        }
        return new Parsed(block, Map.copyOf(properties));
    }

    @SuppressWarnings("unchecked")
    private static BlockState apply(BlockState state, Property<?> property, String value) {
        Object candidate = parseValue(property, value);
        return state.with((Property<Object>) property, candidate);
    }

    private static Object parseValue(Property<?> property, String value) {
        return property.getValues().stream()
                .filter(entry -> BlockStateCodec.valueString(entry).equals(value)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException(I18n.tr(
                        "command.worldedit.invalid_property_value", value, property.getName())));
    }

    private record Parsed(Block block, Map<Property<?>, String> properties) {}

    private WorldEditBlockParser() {}
}
