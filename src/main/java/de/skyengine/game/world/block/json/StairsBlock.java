package de.skyengine.game.world.block.json;

import de.skyengine.game.physics.AABB;
import de.skyengine.game.world.World;
import de.skyengine.game.world.block.Direction;
import de.skyengine.game.world.block.Identifier;
import de.skyengine.game.world.block.model.BakedQuad;
import de.skyengine.game.world.block.model.BlockModels;
import de.skyengine.game.world.block.model.BoxElement;
import de.skyengine.game.world.block.shape.BlockShape;
import de.skyengine.game.world.block.state.BlockHalf;
import de.skyengine.game.world.block.state.BlockState;
import de.skyengine.game.world.block.state.Properties;
import de.skyengine.game.world.block.state.Property;
import de.skyengine.game.world.block.state.StairShape;

import java.util.ArrayList;
import java.util.List;

/**
 * Treppe mit voller Minecraft-Parität: drehbar in alle vier Richtungen,
 * upside-down platzierbar und mit automatischer Innen-/Außen-Eckenformung.
 *
 * <p>Kanonische Referenz: FACING=NORTH, HALF=BOTTOM. Die erhöhte Stufe liegt auf
 * der NORTH-Seite (z 0..0.5). Alle anderen Zustände entstehen durch
 * {@link BoxElement#rotateY} und {@link BoxElement#mirrorY}. Dieselben Boxen
 * liefern Modell und Kollision/Umriss.
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

    /* ---- Geometrie ---- */

    private List<BoxElement> buildElements(BlockState state) {
        int top = this.resolveLayer("top", "all");
        int bottom = this.resolveLayer("bottom", "all");
        int side = this.resolveLayer("side", "all");
        int[] tex = {top, bottom, side, side, side, side};
        int[] slabCull = {BakedQuad.NO_CULL, 1, BakedQuad.NO_CULL, BakedQuad.NO_CULL, BakedQuad.NO_CULL, BakedQuad.NO_CULL};
        int[] noCull = {BakedQuad.NO_CULL, BakedQuad.NO_CULL, BakedQuad.NO_CULL, BakedQuad.NO_CULL, BakedQuad.NO_CULL, BakedQuad.NO_CULL};

        List<BoxElement> els = new ArrayList<>(3);
        els.add(new BoxElement(0, 0, 0, 1, 0.5, 1, tex.clone(), slabCull.clone())); // untere Stufe

        StairShape shape = state.get(Properties.STAIR_SHAPE);
        switch (shape) {
            case STRAIGHT -> els.add(new BoxElement(0, 0.5, 0, 1, 1, 0.5, tex.clone(), noCull.clone()));
            case OUTER_LEFT -> els.add(new BoxElement(0, 0.5, 0, 0.5, 1, 0.5, tex.clone(), noCull.clone()));
            case OUTER_RIGHT -> els.add(new BoxElement(0.5, 0.5, 0, 1, 1, 0.5, tex.clone(), noCull.clone()));
            case INNER_LEFT -> {
                els.add(new BoxElement(0, 0.5, 0, 1, 1, 0.5, tex.clone(), noCull.clone()));
                els.add(new BoxElement(0, 0.5, 0.5, 0.5, 1, 1, tex.clone(), noCull.clone()));
            }
            case INNER_RIGHT -> {
                els.add(new BoxElement(0, 0.5, 0, 1, 1, 0.5, tex.clone(), noCull.clone()));
                els.add(new BoxElement(0.5, 0.5, 0.5, 1, 1, 1, tex.clone(), noCull.clone()));
            }
        }

        boolean top2 = state.get(Properties.HALF) == BlockHalf.TOP;
        int turns = turnsFor(state.get(Properties.FACING));

        List<BoxElement> out = new ArrayList<>(els.size());
        for (BoxElement e : els) {
            if (top2) e = e.mirrorY();
            out.add(e.rotateY(turns));
        }
        return out;
    }

    /** NORTH=0, EAST=1, SOUTH=2, WEST=3 (CW-Vierteldrehungen ab Referenz NORTH). */
    private static int turnsFor(Direction facing) {
        return switch (facing) {
            case NORTH -> 0;
            case EAST -> 1;
            case SOUTH -> 2;
            case WEST -> 3;
            default -> 0;
        };
    }

    @Override
    public BakedQuad[] bakeModel(BlockState state) {
        return BlockModels.bake(this.buildElements(state));
    }

    @Override
    public BlockShape getCollisionShape(BlockState state) {
        List<BoxElement> els = this.buildElements(state);
        AABB[] boxes = new AABB[els.size()];
        for (int i = 0; i < els.size(); i++) boxes[i] = els.get(i).toAABB();
        return new BlockShape(boxes);
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
