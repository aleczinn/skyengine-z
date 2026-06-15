package de.skyengine.game.world.block.json;

import de.skyengine.game.world.World;
import de.skyengine.game.world.block.Identifier;
import de.skyengine.game.world.block.model.BlockStateModels;
import de.skyengine.game.world.block.shape.BlockShape;
import de.skyengine.game.world.block.state.BlockState;
import de.skyengine.game.world.block.state.Properties;
import de.skyengine.game.world.block.state.Property;
import de.skyengine.game.world.block.state.SlabType;

import java.util.List;

/**
 * Slab: untere/obere Hälfte oder voller Doppel-Slab. Geometrie kommt aus dem
 * Blockstate/Modell; Kollision wird aus denselben Modell-Boxen abgeleitet.
 */
public class SlabBlock extends JsonBlock {

    public SlabBlock(Identifier identifier, Settings settings, BlockDefinition definition) {
        super(identifier, settings, definition);
    }

    @Override
    protected void appendProperties(List<Property<?>> properties) {
        properties.add(Properties.SLAB_TYPE);
    }

    @Override
    public boolean isOpaqueCube(BlockState state) {
        return state.get(Properties.SLAB_TYPE) == SlabType.DOUBLE;
    }

    @Override
    public BlockShape getCollisionShape(BlockState state) {
        return new BlockShape(BlockStateModels.bake(this, state).boxes());
    }

    @Override
    public BlockShape getOutlineShape(BlockState state) {
        return this.getCollisionShape(state);
    }

    @Override
    public BlockState getPlacementState(World world, int x, int y, int z,
                                        int faceX, int faceY, int faceZ,
                                        double hitY, float playerYaw) {
        boolean top = faceY < 0 || (faceY == 0 && hitY > 0.5);
        return this.getDefaultState().with(Properties.SLAB_TYPE, top ? SlabType.TOP : SlabType.BOTTOM);
    }
}
