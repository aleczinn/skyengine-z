package de.skyengine.game.world.block.archetype;

import de.skyengine.game.world.block.json.BlockDefinition;

/**
 * Rein JSON-getrieben: Geometrie kommt aus dem Modell-/Blockstate-System, Kollision aus dem
 * {@code collision}-Override (sonst Block-Default). Damit lassen sich beliebige Maschinen,
 * Möbel oder Pipes ohne neuen Archetyp bauen. Verbindungen/BlockEntity über die JSON-Felder.
 */
public final class CustomArchetype implements Archetype {

    @Override
    public void configure(BlockConfig.Builder cfg, BlockDefinition def) {
        // Alles über JSON-Overrides (model/collision/connection/block_entity).
    }
}
