package de.skyengine.game.world.structure;

import de.skyengine.game.world.block.Direction;
import de.skyengine.game.world.block.Identifier;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class WorldEditSelectionTest {
    @Test
    void expandsAndContractsOnlyTheRequestedFace() {
        WorldEditSelection selection = new WorldEditSelection(Identifier.of("test:dimension"))
                .withPos1(0, 10, 0).withPos2(4, 20, 4);
        WorldEditSelection expanded = selection.expand(Direction.EAST, 3);
        assertEquals(7, expanded.bounds().maxX());
        assertEquals(0, expanded.bounds().minX());
        WorldEditSelection contracted = expanded.contract(Direction.DOWN, 2);
        assertEquals(12, contracted.bounds().minY());
        assertEquals(20, contracted.bounds().maxY());
        assertThrows(IllegalArgumentException.class, () -> selection.contract(Direction.NORTH, 5));
    }

    @Test
    void keepsOriginalCornerIdentityWhenMovingFaces() {
        WorldEditSelection reversed = new WorldEditSelection(Identifier.of("test:dimension"))
                .withPos1(5, 20, 5).withPos2(1, 10, 1);
        WorldEditSelection expanded = reversed.expand(Direction.WEST, 2).expand(Direction.UP, 3);
        assertEquals(5, expanded.pos1().x());
        assertEquals(-1, expanded.pos2().x());
        assertEquals(23, expanded.pos1().y());
        assertEquals(10, expanded.pos2().y());
    }

    @Test
    void supportsAllSixAxisDirections() {
        WorldEditSelection base = new WorldEditSelection(Identifier.of("test:dimension"))
                .withPos1(0, 10, 0).withPos2(2, 12, 2);
        assertEquals(-2, base.expand(Direction.WEST, 2).bounds().minX());
        assertEquals(4, base.expand(Direction.EAST, 2).bounds().maxX());
        assertEquals(8, base.expand(Direction.DOWN, 2).bounds().minY());
        assertEquals(14, base.expand(Direction.UP, 2).bounds().maxY());
        assertEquals(-2, base.expand(Direction.NORTH, 2).bounds().minZ());
        assertEquals(4, base.expand(Direction.SOUTH, 2).bounds().maxZ());
        assertEquals(1, base.contract(Direction.WEST, 1).bounds().minX());
        assertEquals(1, base.contract(Direction.EAST, 1).bounds().maxX());
        assertEquals(11, base.contract(Direction.DOWN, 1).bounds().minY());
        assertEquals(11, base.contract(Direction.UP, 1).bounds().maxY());
        assertEquals(1, base.contract(Direction.NORTH, 1).bounds().minZ());
        assertEquals(1, base.contract(Direction.SOUTH, 1).bounds().maxZ());
    }
}
