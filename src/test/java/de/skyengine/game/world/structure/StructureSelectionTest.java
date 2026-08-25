package de.skyengine.game.world.structure;

import de.skyengine.game.world.block.Identifier;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class StructureSelectionTest {
    @Test
    void explicitAnchorMustBeInsideAndResetsWhenBoundsMove() {
        StructureSelection selection = new StructureSelection(Identifier.of("test:dimension"))
                .withPos1(0, 10, 0).withPos2(4, 20, 4).withAnchor(2, 10, 2);
        assertEquals(2, selection.effectiveAnchor().x());
        assertThrows(IllegalArgumentException.class, () -> selection.withAnchor(8, 10, 2));

        StructureSelection moved = selection.withPos1(3, 10, 3);
        assertNull(moved.anchor());
        assertEquals(moved.pos1(), moved.effectiveAnchor());
    }
}
