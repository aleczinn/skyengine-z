package de.skyengine.game.world.chunk;

import de.skyengine.game.world.block.Blocks;
import de.skyengine.game.world.block.model.BakedQuad;
import de.skyengine.game.world.block.state.BlockState;
import de.skyengine.game.world.block.state.Properties;
import de.skyengine.test.BlocksTestBootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class FluidGeometryUnderwaterSurfaceTest {

    @BeforeAll
    static void bootstrapBlocks() {
        BlocksTestBootstrap.ensureBootstrapped();
    }

    @Test
    void exposedTopHasOppositeWindingsUnderAirAndSingleSolid() {
        assertDoubleSidedTop(Blocks.AIR);
        assertDoubleSidedTop(Blocks.STONE);
    }

    @Test
    void closedThreeByThreeCeilingOmitsBackwardFace() {
        Chunk chunk = chunkWithWater(Blocks.STONE);
        for (int z = 9; z <= 11; z++) {
            for (int x = 9; x <= 11; x++) chunk.setBlock(x, 65, z, Blocks.STONE);
        }
        assertEquals(1, Arrays.stream(build(chunk)).filter(FluidGeometryUnderwaterSurfaceTest::isTop).count());
    }

    @Test
    void renderedTopUsesVanillaDepthBias() {
        Chunk chunk = chunkWithWater(Blocks.AIR);
        for (int z = 9; z <= 11; z++) {
            for (int x = 9; x <= 11; x++) chunk.setBlock(x, 64, z, Blocks.WATER);
        }
        BakedQuad top = Arrays.stream(build(chunk))
                .filter(FluidGeometryUnderwaterSurfaceTest::isTop).findFirst().orElseThrow();
        assertEquals(FluidGeometry.SOURCE_HEIGHT - FluidGeometry.TOP_RENDER_EPSILON,
                top.vertices()[1], 0.000001F);
    }

    @Test
    void sameFluidAboveStillCullsInternalTop() {
        Chunk chunk = chunkWithWater(Blocks.WATER);
        BakedQuad[] quads = build(chunk);
        assertEquals(0, Arrays.stream(quads).filter(FluidGeometryUnderwaterSurfaceTest::isTop).count());
    }

    @Test
    void fallingColumnHasSameOwnHeightAsSource() {
        BlockState source = Blocks.getState(Blocks.WATER);
        BlockState falling = source.with(Properties.LEVEL, 1).with(Properties.FALLING, true);

        assertEquals(FluidGeometry.SOURCE_HEIGHT, FluidGeometry.fluidHeight(source), 0.000001F);
        assertEquals(FluidGeometry.SOURCE_HEIGHT, FluidGeometry.fluidHeight(falling), 0.000001F);
    }

    @Test
    void slopedSourceTopUsesStillTextureWhenFlowVectorIsZero() {
        Chunk chunk = chunkWithWater(Blocks.AIR);
        chunk.setBlock(11, 64, 10, Blocks.WATER); // asymmetrische Eckhoehen, aber kein Gefaelle

        BakedQuad top = Arrays.stream(build(chunk))
                .filter(FluidGeometryUnderwaterSurfaceTest::isTop).findFirst().orElseThrow();
        assertEquals(Blocks.getState(Blocks.WATER).getBlock().getFluidInfo().stillLayer,
                top.textureLayer());
    }

    @Test
    void genuineDropEdgeUsesFlowTexture() {
        Chunk chunk = chunkWithWater(Blocks.AIR);
        chunk.setBlock(11, 63, 10, Blocks.WATER); // freie Nachbarzelle mit Wasser darunter

        BakedQuad top = Arrays.stream(build(chunk))
                .filter(FluidGeometryUnderwaterSurfaceTest::isTop).findFirst().orElseThrow();
        assertEquals(Blocks.getState(Blocks.WATER).getBlock().getFluidInfo().flowLayer,
                top.textureLayer());
    }

    private static void assertDoubleSidedTop(int above) {
        Chunk chunk = chunkWithWater(above);
        BakedQuad[] tops = Arrays.stream(build(chunk)).filter(FluidGeometryUnderwaterSurfaceTest::isTop)
                .toArray(BakedQuad[]::new);
        assertEquals(2, tops.length);
        assertTrue(normalY(tops[0]) * normalY(tops[1]) < 0F);
    }

    private static Chunk chunkWithWater(int above) {
        Chunk chunk = new Chunk(0, 0);
        chunk.setBlock(10, 64, 10, Blocks.WATER);
        chunk.setBlock(10, 65, 10, above);
        return chunk;
    }

    private static BakedQuad[] build(Chunk chunk) {
        BlockState water = Blocks.getState(Blocks.WATER);
        return FluidGeometry.build(water, chunk, null, null, null, null,
                new Chunk[4], 10, 64, 10, false);
    }

    private static boolean isTop(BakedQuad quad) {
        float[] v = quad.vertices();
        for (int i = 1; i < v.length; i += 5) if (v[i] <= 0.001F) return false;
        return true;
    }

    private static float normalY(BakedQuad quad) {
        float[] v = quad.vertices();
        float ux = v[5] - v[0], uz = v[7] - v[2];
        float vx = v[10] - v[0], vz = v[12] - v[2];
        return uz * vx - ux * vz;
    }
}
