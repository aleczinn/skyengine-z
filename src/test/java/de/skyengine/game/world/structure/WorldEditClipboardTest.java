package de.skyengine.game.world.structure;

import de.skyengine.game.world.block.Blocks;
import de.skyengine.game.world.block.Identifier;
import de.skyengine.test.BlocksTestBootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class WorldEditClipboardTest {
    @BeforeAll static void bootstrap() { BlocksTestBootstrap.ensureBootstrapped(); }

    @Test
    void externalOriginAlignsWithPasteTargetAndRotatesAroundIt() {
        StructureTemplate template = new StructureTemplate(Identifier.of("test:clipboard"),
                3, 2, 2, 0, 0, 0,
                List.of(new StructureTemplate.Cell(0, 0, 0, Blocks.STONE)));
        WorldEditClipboard clipboard = new WorldEditClipboard(template, -4, 1, 5,
                StructureTransform.IDENTITY);

        assertEquals(new StructureBounds(14, 63, 3, 16, 64, 4),
                WorldEditSession.bounds(clipboard, 10, 64, 8));

        WorldEditClipboard rotated = clipboard.withTransform(new StructureTransform(
                StructureTransform.Rotation.CLOCKWISE_90, StructureTransform.Mirror.NONE));
        assertEquals(new StructureBounds(14, 63, 12, 15, 64, 14),
                WorldEditSession.bounds(rotated, 10, 64, 8));
    }
}
