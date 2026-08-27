package de.skyengine.game.world.lod;

import de.skyengine.game.world.block.BlockRegistry;
import de.skyengine.game.world.block.Identifier;
import de.skyengine.test.BlocksTestBootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

final class LodBlockRulesTest {

    @BeforeAll
    static void bootstrapBlocks() {
        BlocksTestBootstrap.ensureBootstrapped();
    }

    @Test
    void everyConfiguredReplacementResolvesDuringRegistryValidation() {
        assertDoesNotThrow(LodBlockRules::fingerprint);
    }

    @Test
    void representativeNonFullBlocksUseTheirConfiguredFullCubeProxy() {
        int oakDoor = state("voxelstories:oak_door");
        int oakPlanks = state("voxelstories:oak_planks");
        int chest = state("voxelstories:chest");

        assertEquals(oakPlanks, LodBlockRules.simplify(oakDoor));
        assertEquals(oakPlanks, LodBlockRules.simplify(chest));
    }

    private static int state(String id) {
        return BlockRegistry.get(Identifier.of(id)).getDefaultState().getId();
    }
}
