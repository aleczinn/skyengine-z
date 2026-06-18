package de.skyengine.game.world.block.archetype;

import de.skyengine.game.world.block.json.BlockDefinition;

/** Voller Würfel: keine Zusatz-Properties, Block-Defaults (Modell aus Blockstate, FULL_CUBE). */
public final class CubeArchetype implements Archetype {

    @Override
    public void configure(BlockConfig.Builder cfg, BlockDefinition def) {
        // Defaults genügen.
    }
}
