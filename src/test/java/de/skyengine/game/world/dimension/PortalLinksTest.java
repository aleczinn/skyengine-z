package de.skyengine.game.world.dimension;

import de.skyengine.game.world.block.Identifier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class PortalLinksTest {

    private static final Identifier TYPE = Identifier.of("skyengine:nether_portal");
    private static final Identifier OVERWORLD = Identifier.of("skyengine:overworld");
    private static final Identifier NETHER = Identifier.of("skyengine:nether");

    @Test
    void persistsBidirectionalOneToOneLinks(@TempDir Path saveRoot) {
        PortalLinks links = new PortalLinks(saveRoot.toFile());
        links.pair(TYPE, OVERWORLD, "overworld-a", NETHER, "nether-a");

        PortalLinks reloaded = new PortalLinks(saveRoot.toFile());
        assertEquals("nether-a", reloaded.linked(TYPE, OVERWORLD, "overworld-a").portalId());
        assertEquals("overworld-a", reloaded.linked(TYPE, NETHER, "nether-a").portalId());
        assertTrue(reloaded.isLinked(TYPE, OVERWORLD, "overworld-a"));
    }

    @Test
    void repairingOneEndpointCannotLeaveAmbiguousLinks(@TempDir Path saveRoot) {
        PortalLinks links = new PortalLinks(saveRoot.toFile());
        links.pair(TYPE, OVERWORLD, "overworld-a", NETHER, "nether-a");
        links.pair(TYPE, OVERWORLD, "overworld-b", NETHER, "nether-a");

        assertNull(links.linked(TYPE, OVERWORLD, "overworld-a"));
        assertEquals("nether-a", links.linked(TYPE, OVERWORLD, "overworld-b").portalId());
        assertEquals("overworld-b", links.linked(TYPE, NETHER, "nether-a").portalId());

        links.unlink(TYPE, NETHER, "nether-a");
        assertFalse(links.isLinked(TYPE, OVERWORLD, "overworld-b"));
    }

    @Test
    void readsLegacyNamespacesAsCurrentGameIds(@TempDir Path saveRoot) throws Exception {
        Files.writeString(saveRoot.resolve("portal_links.json"), """
                {"version":1,"links":[{"type":"skyengine:nether_portal",
                "first":{"dimension":"skyengine:overworld","portalId":"old-a"},
                "second":{"dimension":"skyengine:nether","portalId":"old-b"}}]}
                """);

        PortalLinks links = new PortalLinks(saveRoot.toFile());

        assertEquals("old-b", links.linked(Identifier.of("voxel_stories:nether_portal"),
                Identifier.of("voxel_stories:overworld"), "old-a").portalId());
    }
}
