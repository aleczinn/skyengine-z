package de.skyengine.game.world.chunk;

import de.skyengine.core.settings.GameSettings;
import de.skyengine.game.world.block.Blocks;
import de.skyengine.game.world.block.Direction;
import de.skyengine.game.world.block.state.PistonType;
import de.skyengine.game.world.block.state.Properties;
import de.skyengine.test.BlocksTestBootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class ChunkMesherPistonAoTest {

    @BeforeAll
    static void bootstrapBlocks() {
        BlocksTestBootstrap.ensureBootstrapped();
    }

    @Test
    void extendedPistonDarkensDirectSupportingFace() {
        int piston = Blocks.getState(Blocks.PISTON)
                .with(Properties.FACING_ALL, Direction.EAST)
                .with(Properties.EXTENDED, true)
                .getId();

        assertEquals(List.of(0xCCCCCC, 0xCCCCCC, 0xCCCCCC, 0xCCCCCC),
                supportingFaceColors(piston, true));
    }

    @Test
    void retractingSourceKeepsExtendedBaseContactAo() {
        int movingSource = Blocks.getState(Blocks.MOVING_PISTON)
                .with(Properties.FACING_ALL, Direction.EAST)
                .with(Properties.PISTON_TYPE, PistonType.NORMAL)
                .with(Properties.RETRACTING_SOURCE, true)
                .getId();

        assertEquals(List.of(0xCCCCCC, 0xCCCCCC, 0xCCCCCC, 0xCCCCCC),
                supportingFaceColors(movingSource, true));
    }

    @Test
    void disabledAoKeepsSupportingFaceUnshaded() {
        int piston = Blocks.getState(Blocks.PISTON)
                .with(Properties.FACING_ALL, Direction.EAST)
                .with(Properties.EXTENDED, true)
                .getId();

        assertEquals(List.of(0xFFFFFF, 0xFFFFFF, 0xFFFFFF, 0xFFFFFF),
                supportingFaceColors(piston, false));
    }

    private static List<Integer> supportingFaceColors(int stateId, boolean ambientOcclusion) {
        GameSettings settings = GameSettings.get();
        boolean previousAo = settings.ambientOcclusion;
        settings.ambientOcclusion = ambientOcclusion;
        try {
            return supportingFaceColors(stateId);
        } finally {
            settings.ambientOcclusion = previousAo;
        }
    }

    private static List<Integer> supportingFaceColors(int stateId) {
        Chunk chunk = new Chunk(0, 0);
        for (int z = 8; z <= 12; z++) {
            for (int x = 8; x <= 12; x++) chunk.setBlock(x, 0, z, Blocks.STONE);
        }
        chunk.setBlock(10, 1, 10, stateId);

        ChunkMesher.MeshData mesh = new ChunkMesher().mesh(chunk, 0,
                null, null, null, null, new Chunk[4]);
        return colorsOfQuad(mesh.opaque, 10F, 1F, 10F, 11F, 1F, 11F);
    }

    private static List<Integer> colorsOfQuad(int[] data,
                                               float minX, float minY, float minZ,
                                               float maxX, float maxY, float maxZ) {
        for (int q = 0; q < data.length; q += 4 * ChunkMesher.VERTEX_SIZE) {
            List<Integer> colors = new ArrayList<>(4);
            float quadMinX = Float.POSITIVE_INFINITY, quadMinY = Float.POSITIVE_INFINITY;
            float quadMinZ = Float.POSITIVE_INFINITY, quadMaxX = Float.NEGATIVE_INFINITY;
            float quadMaxY = Float.NEGATIVE_INFINITY, quadMaxZ = Float.NEGATIVE_INFINITY;
            for (int v = 0; v < 4; v++) {
                int p = q + v * ChunkMesher.VERTEX_SIZE;
                float x = coordinate(data[p] & 0xFFFF);
                float y = coordinate((data[p] >>> 16) & 0xFFFF);
                float z = coordinate(data[p + 1] & 0xFFFF);
                quadMinX = Math.min(quadMinX, x); quadMaxX = Math.max(quadMaxX, x);
                quadMinY = Math.min(quadMinY, y); quadMaxY = Math.max(quadMaxY, y);
                quadMinZ = Math.min(quadMinZ, z); quadMaxZ = Math.max(quadMaxZ, z);
                colors.add(data[p + 3] & 0xFFFFFF);
            }
            boolean matches = quadMinX <= minX && quadMaxX >= maxX
                    && quadMinY == minY && quadMaxY == maxY
                    && quadMinZ <= minZ && quadMaxZ >= maxZ;
            if (matches) return colors;
        }
        return List.of();
    }

    private static float coordinate(int packed) {
        return packed / ChunkMesher.POS_SCALE - 1F;
    }
}
