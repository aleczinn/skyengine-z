package de.skyengine.game.world.dimension;

import de.skyengine.game.world.block.Direction;
import de.skyengine.game.world.block.Identifier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class PortalIndexTest {

    private static final Identifier TYPE = Identifier.of("voxelstories:nether_portal");

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

    @Test
    void relightingSameFramePreservesIdentityAndInactiveFramesAreNotCandidates(@TempDir Path dimensionRoot) {
        PortalIndex index = new PortalIndex(dimensionRoot.toFile());
        NetherPortalShape.Shape shape = new NetherPortalShape.Shape(
                Direction.Axis.X, 10, 50, -4, 2, 3);
        PortalIndex.Entry first = index.add(TYPE, shape);

        PortalIndex.Entry inactive = index.deactivateContaining(TYPE, 10, 50, -4);

        assertEquals(first.id(), inactive.id());
        assertFalse(inactive.active());
        assertNull(index.nearest(TYPE, 10, 50, -4, 32));

        PortalIndex.Entry relit = index.add(TYPE, shape);
        assertEquals(first.id(), relit.id());
        assertTrue(relit.active());
        assertEquals(first.id(), new PortalIndex(dimensionRoot.toFile()).byId(first.id()).id());
    }

    @Test
    void ignoresIncompatibleVersionOneIndex(@TempDir Path dimensionRoot)
            throws Exception {
        java.nio.file.Files.writeString(dimensionRoot.resolve("portals.json"), """
                {
                  "version": 1,
                  "portals": [
                    {"type":"voxelstories:nether_portal","x":4,"y":60,"z":8,
                     "axis":"Z","width":2,"height":3}
                  ]
                }
                """);

        PortalIndex index = new PortalIndex(dimensionRoot.toFile());
        assertNull(index.nearest(TYPE, 4, 60, 8, 16));
    }

    @Test
    void candidateFilterSkipsReservedPortalAndUsesTrueFrameCenter(@TempDir Path dimensionRoot) {
        PortalIndex index = new PortalIndex(dimensionRoot.toFile());
        PortalIndex.Entry wide = index.add(TYPE,
                new NetherPortalShape.Shape(Direction.Axis.X, 0, 50, 0, 10, 3));
        PortalIndex.Entry compact = index.add(TYPE,
                new NetherPortalShape.Shape(Direction.Axis.X, 6, 50, 0, 2, 3));

        assertEquals(wide.id(), index.nearest(TYPE, 5, 50, 0, 32).id());
        PortalIndex.Entry nearest = index.candidates(TYPE, 5, 50, 0, 32,
                entry -> !entry.id().equals(wide.id())).getFirst();

        assertEquals(compact.id(), nearest.id());
        assertNotEquals(wide.id(), nearest.id());
    }
}
