package de.skyengine.game.world.lod;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

final class LodScreenSpaceSelectorTest {

    @Test
    void zoomRefinesWithoutChangingDistanceRings() {
        Map<LodVolumeHierarchy.Key, LodVoxelSection> nodes = completeTree(3);
        LodVoxelSection root = nodes.get(new LodVolumeHierarchy.Key(0, 0, 0, 3));

        var normal = LodScreenSpaceSelector.select(java.util.List.of(root), nodes::get,
                0, 0, -2000, 1080, Math.toRadians(75), 64, 64);
        var zoomed = LodScreenSpaceSelector.select(java.util.List.of(root), nodes::get,
                0, 0, -2000, 1080, Math.toRadians(20), 64, 64);

        assertTrue(zoomed.nodes().size() > normal.nodes().size());
        assertEquals(0, zoomed.missingChildren());
    }

    @Test
    void missingChildrenKeepParentVisible() {
        LodVoxelSection root = new LodVoxelSection(0, 0, 0, 2,
                LodVoxelSection.Completeness.PROVISIONAL);
        var selected = LodScreenSpaceSelector.select(java.util.List.of(root), key -> null,
                0, 0, -10, 1080, Math.toRadians(70), 32, 64);
        assertEquals(java.util.List.of(root), selected.nodes());
        assertEquals(1, selected.missingChildren());
    }

    private static Map<LodVolumeHierarchy.Key, LodVoxelSection> completeTree(int rootLevel) {
        Map<LodVolumeHierarchy.Key, LodVoxelSection> nodes = new HashMap<>();
        for (int level = rootLevel; level >= 0; level--) {
            int side = 1 << (rootLevel - level);
            for (int y = 0; y < side; y++) for (int z = 0; z < side; z++) for (int x = 0; x < side; x++) {
                LodVoxelSection node = new LodVoxelSection(x, y, z, level,
                        LodVoxelSection.Completeness.CANONICAL);
                nodes.put(new LodVolumeHierarchy.Key(x, y, z, level), node);
            }
        }
        return nodes;
    }
}
