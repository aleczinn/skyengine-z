package de.skyengine.game.world.block.archetype;

import de.skyengine.game.world.block.Identifier;
import de.skyengine.game.world.block.behavior.TallPlantBehavior;
import de.skyengine.game.world.block.json.BlockDefinition;
import de.skyengine.game.world.block.model.BlockModels;
import de.skyengine.game.world.block.model.ModelLoader;
import de.skyengine.game.world.block.shape.Shapes;
import de.skyengine.game.world.block.state.BlockHalf;
import de.skyengine.game.world.block.state.Properties;

/**
 * Zwei Blöcke hohe Cross-Pflanze (tall_grass): wie {@link CrossArchetype}, aber mit
 * HALF-Property und {@link TallPlantBehavior} (Tür-Muster). Die Textur kommt je Hälfte
 * aus {@code block/<id>_bottom} bzw. {@code block/<id>_top} (jeweils {@code #all}).
 */
public final class TallCrossArchetype implements Archetype {

    @Override
    public void configure(BlockConfig.Builder cfg, BlockDefinition def) {
        String base = "block/" + Identifier.of(def.id).path();
        cfg.property(Properties.HALF)
                .model(state -> BlockModels.cross(ModelLoader.textureLayer(
                        base + (state.get(Properties.HALF) == BlockHalf.BOTTOM ? "_bottom" : "_top"), "all")))
                .shapes(Shapes.cross())
                .randomOffset(true)
                .behavior(new TallPlantBehavior());
    }
}
