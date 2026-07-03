package de.skyengine.game.world.block.json;

import de.skyengine.game.world.block.Block;
import de.skyengine.game.world.block.BlockTextures;
import de.skyengine.game.world.block.Identifier;
import de.skyengine.utils.logging.LogManager;
import de.skyengine.utils.logging.Logger;

/**
 * Basisklasse aller JSON-definierten Blöcke. Das Modell kommt ab Phase 3 aus dem
 * datengetriebenen Blockstate-/Modell-System ({@link Block#bakeModel}); Subklassen
 * fügen nur noch Verhalten hinzu (Properties, Platzierung, Nachbar-Updates, Formen).
 */
public class JsonBlock extends Block {

    protected final Logger logger = LogManager.getLogger(JsonBlock.class.getName());
    protected final BlockDefinition definition;

    public JsonBlock(Identifier identifier, Settings settings, BlockDefinition definition) {
        super(identifier, settings);
        this.definition = definition;
    }

    /** Sucht eine Textur in der (optionalen) textures-Map der Block-JSON. */
    protected int resolveLayer(String... keys) {
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
