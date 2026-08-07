package de.skyengine.game.world.item.archetype;

import de.skyengine.game.world.block.Identifier;
import de.skyengine.game.world.item.Item;
import de.skyengine.game.world.item.json.ItemDefinition;

/** Fabrik eines datengetriebenen Item-Archetyps. */
@FunctionalInterface
public interface ItemArchetype {
    Item create(Identifier id, ItemDefinition definition);
}
