package de.skyengine.game.world.block.json;

import de.skyengine.game.world.block.Block;
import de.skyengine.game.world.block.BlockTextures;
import de.skyengine.game.world.block.Identifier;
import de.skyengine.game.world.block.model.BakedQuad;
import de.skyengine.game.world.block.model.BlockModels;
import de.skyengine.game.world.block.state.BlockState;
import de.skyengine.utils.logging.LogManager;
import de.skyengine.utils.logging.Logger;

public class JsonBlock extends Block {

    private final Logger logger = LogManager.getLogger(JsonBlock.class.getName());
    private final BlockDefinition definition;

    public JsonBlock(Identifier identifier, Settings settings, BlockDefinition definition) {
        super(identifier, settings);
        this.definition = definition;
    }

    @Override
    public BakedQuad[] bakeModel(BlockState state) {
        return switch (this.definition.type) {
            case "cube" -> BlockModels.cube(
                    this.resolveLayer("top", "all"),
                    this.resolveLayer("bottom", "all"),
                    this.resolveLayer("north", "side", "all"),
                    this.resolveLayer("south", "side", "all"),
                    this.resolveLayer("west", "side", "all"),
                    this.resolveLayer("east", "side", "all")
            );
            case "cross" -> BlockModels.cross(this.resolveLayer("all", "side"));
            default -> {
                this.logger.error("Unbekannter Modell-Typ '" + this.definition.type + "' bei " + this.getIdentifier());
                yield new BakedQuad[0];
            }
        };
    }

    @Override
    public boolean hasRandomOffset(BlockState state) {
        return "cross".equals(this.definition.type);
    }

    /** Sucht die Textur über eine Fallback-Kette (z.B. "top" -> "all"). */
    private int resolveLayer(String... keys) {
        for (String key : keys) {
            String path = this.definition.textures.get(key);
            if (path != null) return BlockTextures.layerOf(path);
        }
        if (!this.definition.textures.isEmpty()) {
            return BlockTextures.layerOf(this.definition.textures.values().iterator().next());
        }
        this.logger.warning("Block " + this.getIdentifier() + " hat keine Texturen definiert!");
        return 0;
    }
}