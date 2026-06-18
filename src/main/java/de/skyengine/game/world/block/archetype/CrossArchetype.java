package de.skyengine.game.world.block.archetype;

import de.skyengine.game.world.block.Identifier;
import de.skyengine.game.world.block.json.BlockDefinition;
import de.skyengine.game.world.block.model.BlockModels;
import de.skyengine.game.world.block.model.ModelLoader;
import de.skyengine.game.world.block.shape.Shapes;

/**
 * Cross (Gras/Blumen/Setzlinge): zwei gekreuzte Quads aus {@link BlockModels#cross},
 * Textur {@code #all} aus {@code block/<id>}, seed-basierter XZ-Offset, keine Kollision.
 */
public final class CrossArchetype implements Archetype {

    @Override
    public void configure(BlockConfig.Builder cfg, BlockDefinition def) {
        String model = "block/" + Identifier.of(def.id).path();
        cfg.model(state -> BlockModels.cross(ModelLoader.textureLayer(model, "all")))
                .shapes(Shapes.cross())
                .randomOffset(true);
    }
}
