package de.skyengine.game.world.block.shape;

import de.skyengine.game.physics.AABB;
import de.skyengine.game.world.block.json.BlockDefinition;
import de.skyengine.game.world.block.model.ModelElements;
import de.skyengine.game.world.block.state.BlockState;

/**
 * Kollisions-/Umriss-Form direkt aus der Block-JSON (Feld {@code collision}). Damit ist die
 * Collision <b>unabhängig vom Modell</b> frei definierbar — beliebige Maschinen/Pipes ohne Code.
 */
public final class JsonShapeProvider implements ShapeProvider {

    private final BlockShape collision;
    private final BlockShape outline;

    private JsonShapeProvider(BlockShape collision, BlockShape outline) {
        this.collision = collision;
        this.outline = outline;
    }

    public static JsonShapeProvider of(BlockDefinition.CollisionDef def) {
        BlockShape collision = toShape(def.boxes);
        BlockShape outline = def.outline != null ? toShape(def.outline) : collision;
        return new JsonShapeProvider(collision, outline);
    }

    private static BlockShape toShape(ModelElements.ModelBox[] boxes) {
        if (boxes == null || boxes.length == 0) return BlockShape.EMPTY;
        AABB[] out = new AABB[boxes.length];
        for (int i = 0; i < boxes.length; i++) {
            ModelElements.ModelBox b = boxes[i];
            out[i] = new AABB(b.x0(), b.y0(), b.z0(), b.x1(), b.y1(), b.z1());
        }
        return new BlockShape(out);
    }

    @Override public BlockShape collision(BlockState state) { return collision; }
    @Override public BlockShape outline(BlockState state) { return outline; }
}
