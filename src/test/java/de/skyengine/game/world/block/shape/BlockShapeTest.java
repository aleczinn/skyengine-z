package de.skyengine.game.world.block.shape;

import de.skyengine.game.physics.AABB;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class BlockShapeTest {

    @Test
    void recognizesFullCubeComposedOfMultipleBoxes() {
        BlockShape shape = new BlockShape(new AABB[]{
                new AABB(0, 0, 0, 1, 0.5, 1),
                new AABB(0, 0.5, 0, 1, 1, 1)
        });

        assertTrue(shape.isFullCube());
    }

    @Test
    void rejectsCompositeShapeWithGap() {
        BlockShape shape = new BlockShape(new AABB[]{
                new AABB(0, 0, 0, 1, 0.4, 1),
                new AABB(0, 0.5, 0, 1, 1, 1)
        });

        assertFalse(shape.isFullCube());
    }
}
