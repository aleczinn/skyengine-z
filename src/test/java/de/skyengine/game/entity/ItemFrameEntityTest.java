package de.skyengine.game.entity;

import de.skyengine.game.world.block.Direction;
import de.skyengine.game.world.block.Identifier;
import de.skyengine.game.world.item.ItemStack;
import de.skyengine.game.world.item.Items;
import de.skyengine.test.BlocksTestBootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
