package de.skyengine.game.world.dimension;

import de.skyengine.game.world.block.Identifier;
import de.skyengine.test.BlocksTestBootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

final class PortalControllerTest {

    @BeforeAll
    static void bootstrap() {
        BlocksTestBootstrap.ensureBootstrapped();
    }

    @Test
    void travelsAfterTwentyContactTicksAndRequiresLeavingBeforeReturn() {
        Identifier overworld = Identifier.of("test:overworld");
        Identifier mining = Identifier.of("test:mining");
        PortalDefinition portal = new PortalDefinition(Identifier.of("test:portal"),
                Identifier.of("test:portal_block"), Map.of(overworld, mining, mining, overworld));
        PortalController controller = new PortalController();

        for (int tick = 1; tick < PortalController.TRAVEL_TICKS; tick++) {
            assertNull(controller.tickContact(overworld, portal, 12, -8));
        }
        PortalController.Travel outward = controller.tickContact(overworld, portal, 12, -8);
        assertEquals(mining, outward.targetDimension());
        assertEquals(12, outward.x());
        assertEquals(-8, outward.z());

        for (int tick = 0; tick < PortalController.TRAVEL_TICKS; tick++) {
            assertNull(controller.tickContact(mining, portal, 12, -8),
                    "Zielportal bleibt bis zum Verlassen gesperrt");
        }
        assertNull(controller.tickContact(mining, null, 0, 0));
        for (int tick = 1; tick < PortalController.TRAVEL_TICKS; tick++) {
            assertNull(controller.tickContact(mining, portal, 12, -8));
        }
        assertEquals(overworld,
                controller.tickContact(mining, portal, 12, -8).targetDimension());
    }

    @Test
    void usePortalIgnoresContactAndTravelsImmediatelyOnActivation() {
        Identifier overworld = WorldgenRegistries.OVERWORLD;
        Identifier mining = WorldgenRegistries.MINING;
        Identifier block = Identifier.of("skyengine:mining_portal");
        PortalDefinition portal = WorldgenRegistries.PORTALS.get(
                Identifier.of("skyengine:mining_portal"));
        PortalController controller = new PortalController();

        for (int tick = 0; tick < PortalController.TRAVEL_TICKS + 5; tick++) {
            assertNull(controller.tickContact(overworld, portal, 4, 9));
        }
        PortalController.Travel travel = controller.useBlock(overworld, block, 4, 9);

        assertEquals(mining, travel.targetDimension());
        assertEquals(4, travel.x());
        assertEquals(9, travel.z());
    }
}
