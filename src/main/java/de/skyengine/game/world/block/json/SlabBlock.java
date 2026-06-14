package de.skyengine.game.world.block.json;

import de.skyengine.game.world.World;
import de.skyengine.game.world.block.Identifier;
import de.skyengine.game.world.block.model.BakedQuad;
import de.skyengine.game.world.block.model.BlockModels;
import de.skyengine.game.world.block.shape.BlockShape;
import de.skyengine.game.world.block.state.BlockState;
import de.skyengine.game.world.block.state.Properties;
import de.skyengine.game.world.block.state.Property;
import de.skyengine.game.world.block.state.SlabType;

import java.util.List;

/** Slab: untere/obere Hälfte oder voller Doppel-Slab. */
public class SlabBlock extends JsonBlock {

    private static final BlockShape BOTTOM_SHAPE = BlockShape.box(0, 0, 0, 1, 0.5, 1);
    private static final BlockShape TOP_SHAPE = BlockShape.box(0, 0.5, 0, 1, 1, 1);

    public SlabBlock(Identifier identifier, Settings settings, BlockDefinition definition) {
        super(identifier, settings, definition);
    }

    @Override
    protected void appendProperties(List<Property<?>> properties) {
        properties.add(Properties.SLAB_TYPE);
    }

    @Override
    public BakedQuad[] bakeModel(BlockState state) {
        int top = this.resolveLayer("top", "all");
        int bottom = this.resolveLayer("bottom", "all");
        int side = this.resolveLayer("side", "all");

        return switch (state.get(Properties.SLAB_TYPE)) {
            case DOUBLE -> BlockModels.cube(top, bottom, side);
            case BOTTOM -> BlockModels.box(0, 0, 0, 1, 0.5, 1,
                    new int[]{top, bottom, side, side, side, side},
                    new int[]{BakedQuad.NO_CULL, 1, BakedQuad.NO_CULL, BakedQuad.NO_CULL, BakedQuad.NO_CULL, BakedQuad.NO_CULL});
            case TOP -> BlockModels.box(0, 0.5, 0, 1, 1, 1,
                    new int[]{top, bottom, side, side, side, side},
                    new int[]{0, BakedQuad.NO_CULL, BakedQuad.NO_CULL, BakedQuad.NO_CULL, BakedQuad.NO_CULL, BakedQuad.NO_CULL});
        };
    }

    @Override
    public boolean isOpaqueCube(BlockState state) {
        return state.get(Properties.SLAB_TYPE) == SlabType.DOUBLE;
    }

    @Override
    public BlockShape getCollisionShape(BlockState state) {
        return shapeFor(state);
    }

    @Override
    public BlockShape getOutlineShape(BlockState state) {
        return shapeFor(state);
    }

    private static BlockShape shapeFor(BlockState state) {
        return switch (state.get(Properties.SLAB_TYPE)) {
            case BOTTOM -> BOTTOM_SHAPE;
            case TOP -> TOP_SHAPE;
            case DOUBLE -> BlockShape.FULL_CUBE;
        };
    }

    @Override
    public BlockState getPlacementState(World world, int x, int y, int z,
                                        int faceX, int faceY, int faceZ,
                                        double hitY, float playerYaw) {
        boolean top = faceY < 0 || (faceY == 0 && hitY > 0.5);
        return this.getDefaultState().with(Properties.SLAB_TYPE, top ? SlabType.TOP : SlabType.BOTTOM);
    }
}
