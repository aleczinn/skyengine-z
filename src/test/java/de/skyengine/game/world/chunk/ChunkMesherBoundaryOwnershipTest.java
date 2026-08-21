package de.skyengine.game.world.chunk;

import de.skyengine.game.world.block.Blocks;
import de.skyengine.test.BlocksTestBootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ChunkMesherBoundaryOwnershipTest {

    @BeforeAll
    static void bootstrapBlocks() {
        BlocksTestBootstrap.ensureBootstrapped();
    }

    @Test
    void recordsExactlyTheHorizontalFacesActuallyEmittedOnAllFourBorders() {
        for (int face = 2; face <= 5; face++) {
            int tangent = 11, y = 7;
            Chunk center = new Chunk(0, 0);
            int x = face == 4 ? 0 : face == 5 ? ChunkSection.MASK : tangent;
            int z = face == 2 ? 0 : face == 3 ? ChunkSection.MASK : tangent;
            center.setBlock(x, y, z, Blocks.STONE);

            ChunkMesher.MeshData open = mesh(center, null, face);
            assertTrue(open.rendersBoundaryFace(face, tangent, y),
                    "open border face " + face + " must be owned by L0");

            Chunk neighbor = neighborWithMatchingStone(face, tangent, y);
            ChunkMesher.MeshData culled = mesh(center, neighbor, face);
            assertFalse(culled.rendersBoundaryFace(face, tangent, y),
                    "opaque neighbor must cull border face " + face);
        }
    }

    @Test
    void fluidBoundaryOwnershipUsesTheActualFluidCullDecision() {
        Chunk center = new Chunk(0, 0);
        center.setBlock(0, 12, 9, Blocks.WATER);
        ChunkMesher.MeshData open = mesh(center, null, 4);
        assertTrue(open.rendersBoundaryFace(4, 9, 12));

        Chunk west = new Chunk(-1, 0);
        west.setBlock(ChunkSection.MASK, 12, 9, Blocks.WATER);
        ChunkMesher.MeshData joined = mesh(center, west, 4);
        assertFalse(joined.rendersBoundaryFace(4, 9, 12));
    }

    @Test
    void emptyRemeshPublishesAZeroSnapshotAndBoundaryRevisionTracksOnlyChanges() {
        Chunk empty = new Chunk(0, 0);
        ChunkMesher.MeshData data = mesh(empty, null, 4);
        assertNotNull(data, "empty remesh must still clear stale boundary ownership");
        assertTrue(data.isEmpty());

        int[] open = data.boundaryFaces().clone();
        open[(4 - 2) * ChunkSection.SIZE + 3] = 1 << 5;
        assertTrue(empty.tryApplyMeshSection(0, 1L, open));
        long firstRevision = empty.boundaryMeshRevision();
        assertTrue(firstRevision > 0);
        assertTrue((empty.boundaryFaceBits(0, 4, 3) & (1 << 5)) != 0);

        assertTrue(empty.tryApplyMeshSection(0, 2L, open));
        long secondRevision = empty.boundaryMeshRevision();
        assertTrue(secondRevision > firstRevision,
                "material/state changes can require a stitch rebuild even with equal bits");

        assertTrue(empty.tryApplyMeshSection(0, 3L, data.boundaryFaces()));
        long clearedRevision = empty.boundaryMeshRevision();
        assertTrue(clearedRevision > secondRevision);
        assertFalse(empty.tryApplyMeshSection(0, 2L, open),
                "stale upload must neither replace geometry nor ownership");
        assertTrue(empty.boundaryMeshRevision() == clearedRevision);
    }

    private static ChunkMesher.MeshData mesh(Chunk center, Chunk neighbor, int face) {
        Chunk north = face == 2 ? neighbor : null;
        Chunk south = face == 3 ? neighbor : null;
        Chunk west = face == 4 ? neighbor : null;
        Chunk east = face == 5 ? neighbor : null;
        return new ChunkMesher().mesh(center, 0, north, south, west, east, new Chunk[4]);
    }

    private static Chunk neighborWithMatchingStone(int face, int tangent, int y) {
        Chunk neighbor = switch (face) {
            case 2 -> new Chunk(0, -1);
            case 3 -> new Chunk(0, 1);
            case 4 -> new Chunk(-1, 0);
            case 5 -> new Chunk(1, 0);
            default -> throw new IllegalArgumentException();
        };
        int x = face == 4 ? ChunkSection.MASK : face == 5 ? 0 : tangent;
        int z = face == 2 ? ChunkSection.MASK : face == 3 ? 0 : tangent;
        neighbor.setBlock(x, y, z, Blocks.STONE);
        return neighbor;
    }
}
