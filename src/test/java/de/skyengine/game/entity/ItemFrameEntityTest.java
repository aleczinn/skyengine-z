package de.skyengine.game.entity;

import de.skyengine.game.world.block.Direction;
import de.skyengine.game.world.block.Identifier;
import de.skyengine.game.world.item.ItemStack;
import de.skyengine.game.world.item.Items;
import de.skyengine.test.BlocksTestBootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ItemFrameEntityTest {

    @BeforeAll
    static void bootstrap() {
        BlocksTestBootstrap.ensureBootstrapped();
    }

    @Test
    void analogOutputRunsFromOneThroughEightAndWraps() {
        ItemFrameEntity frame = new ItemFrameEntity(1, 64, 2, Direction.NORTH);
        frame.loadContent(new ItemStack(Items.get(Identifier.of("skyengine:stone")), 1), 0);

        assertEquals(1, frame.getAnalogOutput());
        for (int rotation = 1; rotation < 8; rotation++) {
            frame.loadContent(frame.getItem(), rotation);
            assertEquals(rotation + 1, frame.getAnalogOutput());
        }
        frame.loadContent(frame.getItem(), 8);
        assertEquals(1, frame.getAnalogOutput());
    }

    @Test
    void vanillaBoundingBoxIsThreeQuartersWideAndOneSixteenthDeep() {
        ItemFrameEntity frame = new ItemFrameEntity(4, 70, 9, Direction.EAST);

        assertEquals(0.0625, frame.getBoundingBox().maxX - frame.getBoundingBox().minX, 1.0E-9);
        assertEquals(0.75, frame.getBoundingBox().maxY - frame.getBoundingBox().minY, 1.0E-9);
        assertEquals(0.75, frame.getBoundingBox().maxZ - frame.getBoundingBox().minZ, 1.0E-9);
    }

    @Test
    void onlyOverlappingFramesWithSameDirectionConflict() {
        ItemFrameEntity north = new ItemFrameEntity(0, 64, 0, Direction.NORTH);
        ItemFrameEntity sameDirection = new ItemFrameEntity(0, 64, 0, Direction.NORTH);
        ItemFrameEntity perpendicular = new ItemFrameEntity(0, 64, 0, Direction.EAST);
        /* Erzwingt fuer den Praedikat-Test eine geometrische Ueberlappung. In normalen
           Blockzellen haelt die 12x12-Geometrie oft bereits Abstand an der Ecke. */
        perpendicular.setPosition(north.x, north.y, north.z);

        assertTrue(north.conflictsWith(sameDirection));
        assertFalse(north.conflictsWith(perpendicular));

        sameDirection.remove();
        assertFalse(north.conflictsWith(sameDirection));
    }
}
