package de.skyengine.game.world.chunk;

import de.skyengine.game.world.block.Blocks;
import de.skyengine.game.world.block.BlockRegistry;
import de.skyengine.game.world.block.Identifier;
import de.skyengine.game.world.block.model.BakedQuad;
import de.skyengine.game.world.block.state.BlockState;
import de.skyengine.game.world.block.state.Properties;
import de.skyengine.test.BlocksTestBootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
    void classifiesOnlyHorizontalSourceHeightTopsForAnalyticSnapping() {
        Chunk flat = chunkWithWater(Blocks.AIR);
        for (int z = 9; z <= 11; z++) {
            for (int x = 9; x <= 11; x++) flat.setBlock(x, 64, z, Blocks.WATER);
        }
        BakedQuad flatTop = Arrays.stream(build(flat))
                .filter(FluidGeometryUnderwaterSurfaceTest::isTop).findFirst().orElseThrow();
        assertTrue(FluidGeometry.isFlatSourceTop(flatTop));

        Chunk sloped = chunkWithWater(Blocks.AIR);
        BakedQuad slopedTop = Arrays.stream(build(sloped))
                .filter(FluidGeometryUnderwaterSurfaceTest::isTop).findFirst().orElseThrow();
        assertFalse(FluidGeometry.isFlatSourceTop(slopedTop));
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
        assertUvBounds(top, 0F, 1F, 0F, 1F);
    }

    @Test
    void genuineDropEdgeUsesMinecraftFlowTextureFootprintForWaterAndLava() {
        for (int fluidId : new int[]{Blocks.WATER, Blocks.LAVA}) {
            Chunk chunk = chunkWithFluid(fluidId, Blocks.AIR);
            chunk.setBlock(11, 63, 10, fluidId); // freie Nachbarzelle mit Fluid darunter

            BakedQuad[] tops = Arrays.stream(build(chunk, Blocks.getState(fluidId)))
                    .filter(FluidGeometryUnderwaterSurfaceTest::isTop).toArray(BakedQuad[]::new);
            assertEquals(2, tops.length); // Vorder- und Rückseite verwenden denselben Ausschnitt.
            for (BakedQuad top : tops) {
                assertEquals(Blocks.getState(fluidId).getBlock().getFluidInfo().flowLayer,
                        top.textureLayer());
                assertFlowTopFootprint(top);
                assertUvBounds(top, 0.25F, 0.75F, 0.25F, 0.75F);
            }
        }
    }

    @Test
    void fluidSidesUseHalfOfTheFlowSprite() {
        Chunk chunk = chunkWithWater(Blocks.AIR);
        BakedQuad east = Arrays.stream(build(chunk))
                .filter(quad -> allX(quad, 1F)).findFirst().orElseThrow();

        float[] vertices = east.vertices();
        for (int i = 0; i < vertices.length; i += 5) {
            float y = vertices[i + 1];
            float u = vertices[i + 3];
            float v = vertices[i + 4];
            assertTrue(close(u, 0F) || close(u, 0.5F));
            assertEquals((1F - y) * 0.5F, v, 0.000001F);
        }
    }

    @Test
    void fluidBottomKeepsTheFullStillSprite() {
        BakedQuad bottom = Arrays.stream(build(chunkWithWater(Blocks.AIR)))
                .filter(FluidGeometryUnderwaterSurfaceTest::isBottom).findFirst().orElseThrow();
        assertUvBounds(bottom, 0F, 1F, 0F, 1F);
    }

    @Test
    void sideNextToIceIsInsetIntoWater() {
        Chunk chunk = chunkWithWater(Blocks.AIR);
        chunk.setBlock(11, 64, 10, ice());

        BakedQuad east = Arrays.stream(build(chunk))
                .filter(quad -> allX(quad, 1F - FluidGeometry.TRANSLUCENT_SIDE_EPSILON))
                .findFirst().orElseThrow();
        assertTrue(allX(east, 1F - FluidGeometry.TRANSLUCENT_SIDE_EPSILON));
    }

    @Test
    void sideNextToAirRemainsOnBlockBoundary() {
        Chunk chunk = chunkWithWater(Blocks.AIR);
        assertTrue(Arrays.stream(build(chunk)).anyMatch(quad -> allX(quad, 1F)));
    }

    private static void assertDoubleSidedTop(int above) {
        Chunk chunk = chunkWithWater(above);
        BakedQuad[] tops = Arrays.stream(build(chunk)).filter(FluidGeometryUnderwaterSurfaceTest::isTop)
                .toArray(BakedQuad[]::new);
        assertEquals(2, tops.length);
        assertTrue(normalY(tops[0]) * normalY(tops[1]) < 0F);
    }

    private static Chunk chunkWithWater(int above) {
        return chunkWithFluid(Blocks.WATER, above);
    }

    private static Chunk chunkWithFluid(int fluid, int above) {
        Chunk chunk = new Chunk(0, 0);
        chunk.setBlock(10, 64, 10, fluid);
        chunk.setBlock(10, 65, 10, above);
        return chunk;
    }

    private static BakedQuad[] build(Chunk chunk) {
        return build(chunk, Blocks.getState(Blocks.WATER));
    }

    private static BakedQuad[] build(Chunk chunk, BlockState state) {
        return FluidGeometry.build(state, chunk, null, null, null, null,
                new Chunk[4], 10, 64, 10, false);
    }

    private static boolean isTop(BakedQuad quad) {
        float[] v = quad.vertices();
        for (int i = 1; i < v.length; i += 5) if (v[i] <= 0.001F) return false;
        return true;
    }

    private static boolean isBottom(BakedQuad quad) {
        float[] vertices = quad.vertices();
        for (int i = 1; i < vertices.length; i += 5) if (!close(vertices[i], 0F)) return false;
        return true;
    }

    private static void assertFlowTopFootprint(BakedQuad quad) {
        float[] v = quad.vertices();
        int[] corners = {0, 5, 10, 20};
        float centerU = 0F, centerV = 0F;
        for (int corner : corners) {
            centerU += v[corner + 3];
            centerV += v[corner + 4];
        }
        assertEquals(0.5F, centerU / 4F, 0.000001F);
        assertEquals(0.5F, centerV / 4F, 0.000001F);
        assertEquals(0.5F, uvDistance(v, corners[0], corners[1]), 0.000001F);
        assertEquals(0.5F, uvDistance(v, corners[1], corners[2]), 0.000001F);
    }

    private static float uvDistance(float[] vertices, int a, int b) {
        float du = vertices[a + 3] - vertices[b + 3];
        float dv = vertices[a + 4] - vertices[b + 4];
        return (float) Math.sqrt(du * du + dv * dv);
    }

    private static void assertUvBounds(BakedQuad quad, float minU, float maxU,
                                       float minV, float maxV) {
        float actualMinU = Float.POSITIVE_INFINITY, actualMaxU = Float.NEGATIVE_INFINITY;
        float actualMinV = Float.POSITIVE_INFINITY, actualMaxV = Float.NEGATIVE_INFINITY;
        float[] vertices = quad.vertices();
        for (int i = 0; i < vertices.length; i += 5) {
            actualMinU = Math.min(actualMinU, vertices[i + 3]);
            actualMaxU = Math.max(actualMaxU, vertices[i + 3]);
            actualMinV = Math.min(actualMinV, vertices[i + 4]);
            actualMaxV = Math.max(actualMaxV, vertices[i + 4]);
        }
        assertEquals(minU, actualMinU, 0.000001F);
        assertEquals(maxU, actualMaxU, 0.000001F);
        assertEquals(minV, actualMinV, 0.000001F);
        assertEquals(maxV, actualMaxV, 0.000001F);
    }

    private static boolean close(float a, float b) {
        return Math.abs(a - b) <= 0.000001F;
    }

    private static float normalY(BakedQuad quad) {
        float[] v = quad.vertices();
        float ux = v[5] - v[0], uz = v[7] - v[2];
        float vx = v[10] - v[0], vz = v[12] - v[2];
        return uz * vx - ux * vz;
    }

    private static boolean allX(BakedQuad quad, float expected) {
        float[] vertices = quad.vertices();
        for (int i = 0; i < vertices.length; i += 5) {
            if (Math.abs(vertices[i] - expected) > 0.000001F) return false;
        }
        return true;
    }

    private static int ice() {
        return BlockRegistry.get(Identifier.of("voxelstories:ice")).getDefaultState().getId();
    }
}
