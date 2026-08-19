package de.skyengine.game.world.lod;

import de.skyengine.game.world.block.Blocks;
import de.skyengine.test.BlocksTestBootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class LodManagerVisibleColumnTest {

    private static LodBlockAppearance appearance;

    @BeforeAll
    static void bootstrapBlocks() {
        BlocksTestBootstrap.ensureBootstrapped();
        appearance = new LodBlockAppearance();
    }

    @Test
    void stateLookupKeepsBedrockAndWaterSeparatedAtTheWorldBottom() {
        LodColumn column = bedrockOceanColumn();

        assertEquals(Blocks.BEDROCK, LodManager.stateAt(column, 0));
        assertEquals(Blocks.WATER, LodManager.stateAt(column, 1));
        assertEquals(Blocks.WATER, LodManager.stateAt(column, 64));
        assertEquals(Blocks.AIR, LodManager.stateAt(column, 65));
    }

    @Test
    void skylightLookupUsesTheSameWaterAttenuationAsTheLodMesh() {
        LodColumn column = bedrockOceanColumn();

        assertEquals(15, LodManager.skyLightAt(column, 65, appearance));
        assertEquals(14, LodManager.skyLightAt(column, 64, appearance));
        assertEquals(13, LodManager.skyLightAt(column, 63, appearance));
        assertEquals(0, LodManager.skyLightAt(column, 49, appearance));
    }

    private static LodColumn bedrockOceanColumn() {
        return new LodColumn(new long[]{
                LodColumn.pack(Blocks.BEDROCK, 0, 1, LodColumn.FLAG_TERRAIN),
                LodColumn.pack(Blocks.WATER, 1, 65, LodColumn.FLAG_SKY_OPEN)
        });
    }
}
