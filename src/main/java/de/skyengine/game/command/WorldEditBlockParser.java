package de.skyengine.game.command;

import de.skyengine.core.i18n.I18n;
import de.skyengine.game.world.block.Block;
import de.skyengine.game.world.block.Identifier;
import de.skyengine.game.world.block.registry.Registries;
import de.skyengine.game.world.block.state.BlockState;
import de.skyengine.game.world.block.state.BlockStateCodec;
import de.skyengine.game.world.block.state.Property;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** Strikter Command-Parser; anders als der Save-Codec ignoriert er keine unbekannten Properties. */
final class WorldEditBlockParser {
    static BlockState parse(String input) {
        String value = input == null ? "" : input.trim().toLowerCase(Locale.ROOT);
        if (value.isEmpty()) throw new IllegalArgumentException(I18n.tr("command.worldedit.setblock_usage"));
        int open = value.indexOf('[');
        int close = value.lastIndexOf(']');
        if ((open < 0) != (close < 0) || close >= 0 && close != value.length() - 1
                || open >= 0 && value.indexOf('[', open + 1) >= 0 || close >= 0 && close < open) {
            throw new IllegalArgumentException(I18n.tr("command.worldedit.invalid_state_syntax", input));
        }
        String idPart = open < 0 ? value : value.substring(0, open);
        Block block = Registries.BLOCK.get(Identifier.of(idPart));
        if (block == null) throw new IllegalArgumentException(I18n.tr("command.worldedit.unknown_block", idPart));
        BlockState state = block.getDefaultState();
        if (open < 0) return state;

        String inner = value.substring(open + 1, close);
        if (inner.isEmpty()) return state;
        Set<String> seen = new HashSet<>();
        for (String pair : inner.split(",", -1)) {
            int equals = pair.indexOf('=');
            if (equals <= 0 || equals != pair.lastIndexOf('=') || equals == pair.length() - 1) {
                throw new IllegalArgumentException(I18n.tr("command.worldedit.invalid_property", pair));
            }
            String name = pair.substring(0, equals), propertyValue = pair.substring(equals + 1);
            if (!seen.add(name)) throw new IllegalArgumentException(
                    I18n.tr("command.worldedit.duplicate_property", name));
            Property<?> property = state.getValues().keySet().stream()
                    .filter(candidate -> candidate.getName().equals(name)).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException(
                            I18n.tr("command.worldedit.unknown_property", name)));
            state = apply(state, property, propertyValue);
        }
        return state;
    }

    @SuppressWarnings("unchecked")
    private static BlockState apply(BlockState state, Property<?> property, String value) {
        Object candidate = property.getValues().stream()
                .filter(entry -> BlockStateCodec.valueString(entry).equals(value)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException(I18n.tr(
                        "command.worldedit.invalid_property_value", value, property.getName())));
        return state.with((Property<Object>) property, candidate);
    }

    private WorldEditBlockParser() {}
}
