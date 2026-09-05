package de.skyengine.client.network;

import de.skyengine.shared.network.CoreProtocol;
import de.skyengine.shared.network.packets.CorePackets;
import de.skyengine.shared.world.BlockChange;
import de.skyengine.shared.world.BlockEntitySnapshot;
import de.skyengine.shared.world.ChunkColumnSnapshot;
import de.skyengine.shared.world.ChunkSectionSnapshot;
import de.skyengine.shared.world.ImmutableChunkColumnData;
import de.skyengine.shared.world.ImmutableChunkSectionData;
import de.skyengine.shared.world.LightPlane;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;

/** Verifies the semantic boundary shared by LocalTransport and the TCP wire path. */
class ChunkTransportConvergenceTest {
    @Test
    void localReferenceAndValidatedWireDecodeConvergeAfterOvertakingDeltas() throws Exception {
        ImmutableChunkColumnData serverRevision = serverRevision();
        ReplicatedChunkCache local = new ReplicatedChunkCache(null);
        local.setTrustedImmutableTransfer(true);
        ReplicatedChunkCache remote = new ReplicatedChunkCache(null);

        begin(local, 1, 101);
        local.accept(new CorePackets.ChunkColumnData(1, serverRevision));
        applyOvertakingDeltas(local);
        local.accept(new CorePackets.ChunkBatchEnd(1));
        local.drainCompletedBatchIds();

        begin(remote, 2, 102);
        applyOvertakingDeltas(remote);
        byte[] wire = CoreProtocol.encodeChunkSnapshot(serverRevision);
        int fragmentSize = Math.max(1, (wire.length + 2) / 3);
        int fragmentCount = (wire.length + fragmentSize - 1) / fragmentSize;
        for (int index = 0, offset = 0; index < fragmentCount; index++) {
            int end = Math.min(wire.length, offset + fragmentSize);
            remote.accept(CorePackets.ChunkColumnFragment.takeOwnership(
                    2, index, fragmentCount, wire.length,
                    Arrays.copyOfRange(wire, offset, end)));
            offset = end;
        }
        remote.accept(new CorePackets.ChunkBatchEnd(2));
        remote.drainCompletedBatchIds();

        ChunkColumnSnapshot localResult = local.get(serverRevision.dimension(),
                serverRevision.chunkX(), serverRevision.chunkZ());
        ChunkColumnSnapshot remoteResult = remote.get(serverRevision.dimension(),
                serverRevision.chunkX(), serverRevision.chunkZ());
        assertChunkEquals(localResult, remoteResult);
        assertEquals(7, localResult.revision());
        assertEquals(4, serverRevision.revision(),
                "client deltas must not mutate the shared immutable server revision");
        assertNotSame(serverRevision, localResult,
                "prediction/delta state must use a new immutable client revision");
    }

    private static void begin(ReplicatedChunkCache cache, long batchId, long leaseId)
            throws Exception {
        cache.accept(new CorePackets.ChunkBatchStart(batchId, leaseId, 5,
                "skyengine:overworld", 3, -2, 1));
    }

    private static void applyOvertakingDeltas(ReplicatedChunkCache cache) throws Exception {
        cache.accept(new CorePackets.BlockUpdate("skyengine:overworld", 3, -2, 6,
                new BlockChange(7, 35, 11, 12)));
        cache.accept(new CorePackets.MultiBlockUpdate("skyengine:overworld", 3, -2, 7,
                List.of(new BlockChange(8, 36, 12, 0),
                        new BlockChange(9, 37, 13, 15))));
    }

    private static ImmutableChunkColumnData serverRevision() {
        long[] indices = new long[ChunkSectionSnapshot.VOLUME / 64];
        Arrays.fill(indices, 0xAAAAAAAAAAAAAAAAL);
        byte[] sky = new byte[LightPlane.PACKED_BYTES];
        byte[] block = new byte[LightPlane.PACKED_BYTES];
        Arrays.fill(sky, (byte) 0xFF);
        for (int index = 0; index < block.length; index++) block[index] = (byte) (index * 31);
        ImmutableChunkSectionData section = ImmutableChunkSectionData.takeOwnership(
                1, ChunkSectionSnapshot.VOLUME / 2, new int[] {0, 5}, 1, indices,
                LightPlane.takeOwnership(LightPlane.Mode.PACKED_NIBBLES, sky),
                LightPlane.takeOwnership(LightPlane.Mode.PACKED_NIBBLES, block));

        int[] biomes = new int[ChunkColumnSnapshot.COLUMN_CELLS];
        int[] grass = new int[ChunkColumnSnapshot.TINT_CORNERS];
        int[] foliage = new int[ChunkColumnSnapshot.TINT_CORNERS];
        int[] heightmap = new int[ChunkColumnSnapshot.COLUMN_CELLS];
        for (int index = 0; index < biomes.length; index++) {
            biomes[index] = index % 7;
            heightmap[index] = 60 + index % 19;
        }
        for (int index = 0; index < grass.length; index++) {
            grass[index] = 0x335500 + index;
            foliage[index] = 0x224400 + index * 3;
        }
        return ImmutableChunkColumnData.takeOwnership("skyengine:overworld", 3, -2, 4,
                List.of(section), biomes, grass, foliage, heightmap,
                List.of(new BlockEntitySnapshot(4, 40, 5, "skyengine:test", new byte[] {1, 2, 3})));
    }

    private static void assertChunkEquals(ChunkColumnSnapshot expected,
                                          ChunkColumnSnapshot actual) {
        assertEquals(expected.dimension(), actual.dimension());
        assertEquals(expected.chunkX(), actual.chunkX());
        assertEquals(expected.chunkZ(), actual.chunkZ());
        assertEquals(expected.revision(), actual.revision());
        assertArrayEquals(expected.biomeIds(), actual.biomeIds());
        assertArrayEquals(expected.grassTintCorners(), actual.grassTintCorners());
        assertArrayEquals(expected.foliageTintCorners(), actual.foliageTintCorners());
        assertArrayEquals(expected.heightmap(), actual.heightmap());
        assertEquals(expected.blockEntities().size(), actual.blockEntities().size());
        for (int index = 0; index < expected.blockEntities().size(); index++) {
            BlockEntitySnapshot left = expected.blockEntities().get(index);
            BlockEntitySnapshot right = actual.blockEntities().get(index);
            assertEquals(left.localX(), right.localX());
            assertEquals(left.y(), right.y());
            assertEquals(left.localZ(), right.localZ());
            assertEquals(left.typeId(), right.typeId());
            assertArrayEquals(left.data(), right.data());
        }

        List<ChunkSectionSnapshot> leftSections = new ArrayList<>(expected.sections());
        List<ChunkSectionSnapshot> rightSections = new ArrayList<>(actual.sections());
        leftSections.sort(java.util.Comparator.comparingInt(ChunkSectionSnapshot::sectionY));
        rightSections.sort(java.util.Comparator.comparingInt(ChunkSectionSnapshot::sectionY));
        assertEquals(leftSections.size(), rightSections.size());
        for (int index = 0; index < leftSections.size(); index++) {
            ChunkSectionSnapshot left = leftSections.get(index);
            ChunkSectionSnapshot right = rightSections.get(index);
            assertEquals(left.sectionY(), right.sectionY());
            assertEquals(left.nonAir(), right.nonAir());
            assertEquals(left.bitsPerEntry(), right.bitsPerEntry());
            assertArrayEquals(left.palette(), right.palette());
            assertArrayEquals(left.packedPaletteIndices(), right.packedPaletteIndices());
            assertEquals(left.skyLight().mode(), right.skyLight().mode());
            assertArrayEquals(left.skyLight().packedNibbles(), right.skyLight().packedNibbles());
            assertEquals(left.blockLight().mode(), right.blockLight().mode());
            assertArrayEquals(left.blockLight().packedNibbles(), right.blockLight().packedNibbles());
        }
    }
}
