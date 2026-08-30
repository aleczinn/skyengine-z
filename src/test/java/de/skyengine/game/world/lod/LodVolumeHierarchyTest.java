package de.skyengine.game.world.lod;

import de.skyengine.game.world.chunk.Chunk;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class LodVolumeHierarchyTest {

    @Test
    void contentVersionChangesForCreationReplacementAndInvalidation() {
        LodVolumeHierarchy hierarchy = new LodVolumeHierarchy((request, writer) -> {});
        LodVolumeHierarchy.Key key = new LodVolumeHierarchy.Key(2, 0, -3, 0);

        hierarchy.getOrCreateAnalytic(key.x(), key.y(), key.z(), key.level());
        long analytic = hierarchy.contentVersion(key);
        assertTrue(analytic > 0);

        hierarchy.publish(new LodVoxelSection(key.x(), key.y(), key.z(), key.level(),
                LodVoxelSection.Completeness.CANONICAL));
        long canonical = hierarchy.contentVersion(key);
        assertTrue(canonical > analytic);

        hierarchy.invalidateColumn(key.x(), key.z());
        assertTrue(hierarchy.contentVersion(key) > canonical);
    }

    @Test
    void emptyChunkSectionBecomesTinyCanonicalNodeWithoutScanningMaterializedBlocks() {
        LodVoxelSection section = LodVolumeHierarchy.fromChunk(new Chunk(4, -7), 11);

        assertEquals(LodVoxelSection.Completeness.CANONICAL, section.completeness());
        assertEquals(8, section.estimatedBytes());
        assertEquals(0L, section.get(12, 18, 3));
    }
}
