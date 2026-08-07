package de.skyengine.game.world.item;

import de.skyengine.game.world.block.Identifier;

import java.util.LinkedHashMap;
import java.util.Map;

/** Kleine Registry der bereits spielmechanisch ausgewerteten Verzauberungen. */
public final class Enchantments {

    private static final Map<Identifier, Enchantment> VALUES = new LinkedHashMap<>();

    public static final Enchantment SILK_TOUCH = register("skyengine:silk_touch", 1);
    public static final Enchantment FORTUNE = register("skyengine:fortune", 3);

    private static Enchantment register(String id, int maxLevel) {
        Enchantment enchantment = new Enchantment(Identifier.of(id), maxLevel);
        if (VALUES.putIfAbsent(enchantment.id(), enchantment) != null) {
            throw new IllegalStateException("Verzauberung doppelt registriert: " + id);
        }
        return enchantment;
    }

    public static Enchantment get(Identifier id) {
        return VALUES.get(id);
    }

    private Enchantments() {}
}
