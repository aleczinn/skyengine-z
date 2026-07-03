package de.skyengine.game.world.block.state;

import de.skyengine.game.world.block.Block;
import de.skyengine.game.world.block.Identifier;
import de.skyengine.game.world.block.registry.Registries;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Serialisiert einen {@link BlockState} zu/aus einem stabilen String
 * {@code namespace:path[prop=val,...]}. Persistenz nutzt diesen Codec statt der
 * flüchtigen Runtime-ID — Saves überleben damit das Hinzufügen/Umordnen von Blöcken
 * und Mods (load-order-unabhängig).
 */
public final class BlockStateCodec {

    public static String encode(BlockState state) {
        StringBuilder sb = new StringBuilder(state.getBlock().getIdentifier().toString());

        Map<Property<?>, Object> values = state.getValues();
        if (!values.isEmpty()) {
            List<Property<?>> props = new ArrayList<>(values.keySet());
            props.sort(Comparator.comparing(Property::getName));
            sb.append('[');
            for (int i = 0; i < props.size(); i++) {
                if (i > 0) sb.append(',');
                Property<?> p = props.get(i);
                sb.append(p.getName()).append('=').append(valueString(values.get(p)));
            }
            sb.append(']');
        }
        return sb.toString();
    }

    public static BlockState decode(String encoded) {
        int bracket = encoded.indexOf('[');
        String idPart = bracket < 0 ? encoded : encoded.substring(0, bracket);

        Block block = Registries.BLOCK.get(Identifier.of(idPart));
        if (block == null) return null;

        BlockState state = block.getDefaultState();
        if (bracket < 0) return state;

        String inner = encoded.substring(bracket + 1, encoded.length() - 1);
        if (inner.isEmpty()) return state;

        for (String pair : inner.split(",")) {
            int eq = pair.indexOf('=');
            if (eq < 0) continue;
            state = applyProperty(state, pair.substring(0, eq), pair.substring(eq + 1));
        }
        return state;
    }

    @SuppressWarnings("unchecked")
    private static BlockState applyProperty(BlockState state, String name, String value) {
        for (Property<?> p : state.getValues().keySet()) {
            if (!p.getName().equals(name)) continue;
            for (Object candidate : p.getValues()) {
                if (valueString(candidate).equals(value)) {
                    return state.with((Property<Object>) p, candidate);
                }
            }
        }
        return state;
    }

    private static String valueString(Object value) {
        if (value instanceof Boolean b) return b.toString();
        if (value instanceof Enum<?> e) return e.name().toLowerCase();
        return value.toString();
    }

    private BlockStateCodec() {}
}
