package de.skyengine.game.world.structure;

import de.skyengine.game.world.block.Direction;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class StructureTransformTest {
    @Test void rotatesCoordinatesAndDirectionsTogether() {
        StructureTransform transform = new StructureTransform(StructureTransform.Rotation.CLOCKWISE_90,
                StructureTransform.Mirror.NONE);
        assertEquals(-3, transform.transformedX(2, 3));
        assertEquals(2, transform.transformedZ(2, 3));
        assertEquals(Direction.EAST, transform.direction(Direction.NORTH));
    }

    @Test void mirrorsBeforeRotating() {
        StructureTransform transform = new StructureTransform(StructureTransform.Rotation.CLOCKWISE_90,
                StructureTransform.Mirror.FRONT_BACK);
        assertEquals(-3, transform.transformedX(2, 3));
        assertEquals(-2, transform.transformedZ(2, 3));
    }

    @Test void composesRotationAndWorldAxisMirror() {
        StructureTransform first = new StructureTransform(StructureTransform.Rotation.CLOCKWISE_90,
                StructureTransform.Mirror.NONE);
        StructureTransform second = new StructureTransform(StructureTransform.Rotation.NONE,
                StructureTransform.Mirror.LEFT_RIGHT);
        StructureTransform composed = first.then(second);
        assertEquals(second.transformedX(first.transformedX(2, 3), first.transformedZ(2, 3)),
                composed.transformedX(2, 3));
        assertEquals(second.transformedZ(first.transformedX(2, 3), first.transformedZ(2, 3)),
                composed.transformedZ(2, 3));
    }
}
