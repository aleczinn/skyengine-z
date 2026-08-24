package de.skyengine.game.world.dimension;

import de.skyengine.game.world.block.Direction;
import de.skyengine.game.world.block.Identifier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class PortalIndexTest {

    private static final Identifier TYPE = Identifier.of("skyengine:nether_portal");

    @Test
    void persistsFindsAndRemovesPortals(@TempDir Path dimensionRoot) {
        PortalIndex index = new PortalIndex(dimensionRoot.toFile());
        index.add(TYPE, new NetherPortalShape.Shape(Direction.Axis.X, 10, 50, -4, 2, 3));
        index.add(TYPE, new NetherPortalShape.Shape(Direction.Axis.Z, 80, 55, 2, 3, 4));

        assertTrue(java.nio.file.Files.isRegularFile(dimensionRoot.resolve("portals.json")));
        PortalIndex reloaded = new PortalIndex(dimensionRoot.toFile());
        PortalIndex.Entry nearest = reloaded.nearest(TYPE, 12, 52, -3, 32);
        assertEquals(10, nearest.x());
        assertEquals(Direction.Axis.X, nearest.portalAxis());

        reloaded.removeContaining(TYPE, 11, 51, -4);
        assertNull(new PortalIndex(dimensionRoot.toFile()).nearest(TYPE, 10, 50, -4, 32));
    }
}
