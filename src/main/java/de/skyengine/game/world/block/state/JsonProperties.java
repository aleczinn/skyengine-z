package de.skyengine.game.world.block.state;

import de.skyengine.utils.logging.LogManager;
import de.skyengine.utils.logging.Logger;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Properties, die eine Block-JSON selbst deklariert ({@code "properties": {...}}). Werte sind
 * immer Strings — damit liefern {@link BlockStateCodec} (Persistenz) und
 * {@link de.skyengine.game.world.block.model.BlockStateModels} (Variant-Key) ohne Sonderfall
 * denselben Text, der auch in der JSON steht.
 *
 * <p><b>Interning ist Pflicht, nicht Optimierung:</b> {@link Property} wird per Identität
 * verglichen. Zwei Blöcke, die beide {@code lit} deklarieren, müssen dasselbe Property-Objekt
 * bekommen, sonst könnte ein gemeinsames Behavior den Wert des jeweils anderen nicht lesen.
 */
public final class JsonProperties {

    private static final Logger LOGGER = LogManager.getLogger(JsonProperties.class.getName());

    private static final Map<String, Property<String>> CACHE = new HashMap<>();

    /**
     * Namen, die {@link Properties} bereits vergibt. Ein zweites Property mit gleichem Namen
     * wäre ein anderes Objekt, und {@link BlockStateCodec} sucht beim Laden nur nach dem NAMEN
     * und nimmt den ersten Treffer — der Block bekäme beim Weltladen stillschweigend den
     * falschen Wert gesetzt. Deshalb hart ablehnen statt hoffen.
     */
    private static final Set<String> RESERVED = Set.of(
            "facing", "type", "half", "shape", "axis", "level", "falling", "open", "hinge",
            "north", "east", "south", "west", "up", "down");

    /** {@code null}, wenn der Name reserviert oder die Werteliste unbrauchbar ist (wird geloggt). */
    public static Property<String> of(String name, List<String> values) {
        if (RESERVED.contains(name)) {
            LOGGER.error("Property-Name '" + name + "' ist von der Engine belegt und wird ignoriert");
            return null;
        }
        if (values == null || values.isEmpty()) {
            LOGGER.error("Property '" + name + "' hat keine Werte und wird ignoriert");
            return null;
        }
        if (values.size() != Set.copyOf(values).size()) {
            LOGGER.error("Property '" + name + "' hat doppelte Werte und wird ignoriert");
            return null;
        }
        return CACHE.computeIfAbsent(name + "|" + String.join(",", values), k -> Property.of(name, values));
    }

    private JsonProperties() {}
}
