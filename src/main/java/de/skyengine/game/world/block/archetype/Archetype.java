package de.skyengine.game.world.block.archetype;

import de.skyengine.game.world.block.json.BlockDefinition;

/**
 * Datengetriebener Generator, der aus einer {@link BlockDefinition} die Zusammensetzung
 * eines Blocks erzeugt (Properties, Geometrie, Shapes, Verhalten). Ersetzt die früheren
 * Typ-Subklassen. Registriert in {@code Registries.BLOCK_ARCHETYPE}; modding-erweiterbar.
 */
public interface Archetype {
    void configure(BlockConfig.Builder cfg, BlockDefinition def);
}
