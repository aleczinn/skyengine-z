package de.skyengine.game.world.block.json;

import de.skyengine.game.world.World;
import de.skyengine.game.world.block.Direction;
import de.skyengine.game.world.block.Identifier;
import de.skyengine.game.world.block.model.BlockStateModels;
import de.skyengine.game.world.block.shape.BlockShape;
import de.skyengine.game.world.block.state.BlockHalf;
import de.skyengine.game.world.block.state.BlockState;
import de.skyengine.game.world.block.state.Properties;
import de.skyengine.game.world.block.state.Property;
import de.skyengine.game.world.block.state.StairShape;

import java.util.List;

/**
 * Treppe mit voller Minecraft-Parität: drehbar in alle vier Richtungen,
 * upside-down platzierbar und mit automatischer Innen-/Außen-Eckenformung.
 *
 * <p>Geometrie + Rotation kommen aus dem Blockstate/Modell (facing→y, half=top→x:180,
 * shape→stairs/inner_stairs/outer_stairs). Kollision wird aus denselben Modell-Boxen
 * abgeleitet. Java liefert nur noch das Verhalten: Platzierung und Ecken-Logik.
 */
public class StairsBlock extends JsonBlock {

    public StairsBlock(Identifier identifier, Settings settings, BlockDefinition definition) {
        super(identifier, settings, definition);
    }

    @Override
    protected void appendProperties(List<Property<?>> properties) {
        properties.add(Properties.FACING);
        properties.add(Properties.HALF);
        properties.add(Properties.STAIR_SHAPE);
    }

    @Override
    public boolean isOpaqueCube(BlockState state) {
        return false;
    }

    @Override
    public BlockShape getCollisionShape(BlockState state) {
        return new BlockShape(BlockStateModels.bake(this, state).boxes());
    }

    @Override
    public BlockShape getOutlineShape(BlockState state) {
        return this.getCollisionShape(state);
    }

    /* ---- Platzierung & Eckenformung ---- */

    @Override
    public BlockState getPlacementState(World world, int x, int y, int z,
                                        int faceX, int faceY, int faceZ,
                                        double hitY, float playerYaw) {
        Direction facing = Direction.fromYaw(playerYaw);
        BlockHalf half = (faceY < 0 || (faceY == 0 && hitY > 0.5)) ? BlockHalf.TOP : BlockHalf.BOTTOM;

        BlockState state = this.getDefaultState()
                .with(Properties.FACING, facing)
                .with(Properties.HALF, half)
                .with(Properties.STAIR_SHAPE, StairShape.STRAIGHT);
        return state.with(Properties.STAIR_SHAPE, getStairShape(world, x, y, z, state));
    }

    @Override
    public BlockState getStateForNeighborUpdate(World world, int x, int y, int z, BlockState state) {
        return state.with(Properties.STAIR_SHAPE, getStairShape(world, x, y, z, state));
    }

    /* Portierter Minecraft-Algorithmus (StairBlock.getStairsShape). */
    private static StairShape getStairShape(World world, int x, int y, int z, BlockState state) {
        Direction facing = state.get(Properties.FACING);
        BlockHalf half = state.get(Properties.HALF);

        BlockState front = stairAt(world, x + facing.offsetX(), y + facing.offsetY(), z + facing.offsetZ());
        if (front != null && front.get(Properties.HALF) == half) {
            Direction f = front.get(Properties.FACING);
            if (f.axis() != facing.axis()
                    && isDifferent(world, x, y, z, state, f.opposite())) {
                return f == facing.rotateYCCW() ? StairShape.OUTER_LEFT : StairShape.OUTER_RIGHT;
            }
        }

        Direction back = facing.opposite();
        BlockState rear = stairAt(world, x + back.offsetX(), y + back.offsetY(), z + back.offsetZ());
        if (rear != null && rear.get(Properties.HALF) == half) {
            Direction f = rear.get(Properties.FACING);
            if (f.axis() != facing.axis()
                    && isDifferent(world, x, y, z, state, f)) {
                return f == facing.rotateYCCW() ? StairShape.INNER_LEFT : StairShape.INNER_RIGHT;
            }
        }
        return StairShape.STRAIGHT;
    }

    /** true, wenn in Richtung dir KEINE gleich orientierte Treppe steht. */
    private static boolean isDifferent(World world, int x, int y, int z, BlockState state, Direction dir) {
        BlockState s = stairAt(world, x + dir.offsetX(), y + dir.offsetY(), z + dir.offsetZ());
        return s == null
                || s.get(Properties.FACING) != state.get(Properties.FACING)
                || s.get(Properties.HALF) != state.get(Properties.HALF);
    }

    /** Liefert den BlockState an der Position, wenn es eine Treppe ist, sonst null. */
    private static BlockState stairAt(World world, int x, int y, int z) {
        BlockState s = de.skyengine.game.world.block.Blocks.getState(world.getBlock(x, y, z));
        return s.getBlock() instanceof StairsBlock ? s : null;
    }
}
