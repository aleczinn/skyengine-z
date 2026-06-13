package de.skyengine.game.world.block.json;

import de.skyengine.game.world.block.Identifier;
import de.skyengine.game.world.block.model.BakedQuad;
import de.skyengine.game.world.block.model.BlockModels;
import de.skyengine.game.world.block.shape.BlockShape;
import de.skyengine.game.world.block.state.BlockState;

/**
 * Cross-Modell (Gras, Blumen, Setzlinge): zwei gekreuzte Quads, nicht solide,
 * mit seed-basiertem XZ-Offset. Kollision leer, Umriss eine kleine Box.
 */
public class CrossBlock extends JsonBlock {

    private static final BlockShape OUTLINE = BlockShape.box(0.1, 0.0, 0.1, 0.9, 0.8, 0.9);

    public CrossBlock(Identifier identifier, Settings settings, BlockDefinition definition) {
        super(identifier, settings, definition);
    }

    @Override
    public BakedQuad[] bakeModel(BlockState state) {
        return BlockModels.cross(this.resolveLayer("all", "side"));
    }

    @Override
    public boolean hasRandomOffset(BlockState state) {
        return true;
    }

    @Override
    public BlockShape getCollisionShape(BlockState state) {
        return BlockShape.EMPTY;
    }

    @Override
    public BlockShape getOutlineShape(BlockState state) {
        return OUTLINE;
    }
}
