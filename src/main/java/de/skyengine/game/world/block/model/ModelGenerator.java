package de.skyengine.game.world.block.model;

import de.skyengine.game.world.block.state.BlockState;

/**
 * Erzeugt die Render-Geometrie eines States. Default ist das datengetriebene
 * Blockstate-/Modell-System ({@link BlockStateModels}); Archetypen oder JSON-Overrides
 * können einen eigenen Generator setzen (z.B. das prozedurale Cross-Modell).
 */
@FunctionalInterface
public interface ModelGenerator {
    BakedQuad[] bake(BlockState state);
}
