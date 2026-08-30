package de.skyengine.game.world.lod;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;

import static org.junit.jupiter.api.Assertions.*;

final class LodVolumeStoreTest {

    @TempDir File temporary;

    @Test
    void roundTripsNegativeCoordinatesAndCanonicalState() {
        LodVoxelSection section = new LodVoxelSection(-17, 3, -1, 2,
                LodVoxelSection.Completeness.CANONICAL);
        long voxel = LodVoxel.pack(1234, 15, 1, 2, 3, 177,
                LodVoxel.PROVENANCE_SAVED, 41);
        section.set(31, 7, 0, voxel);

        try (LodVolumeStore store = new LodVolumeStore(temporary, 0xCAFE)) {
            store.write(section);
            LodVoxelSection loaded = store.read(-17, 3, -1, 2);
            assertNotNull(loaded);
            assertEquals(LodVoxelSection.Completeness.CANONICAL, loaded.completeness());
            assertEquals(voxel, loaded.get(31, 7, 0));
            assertEquals(0L, loaded.get(0, 0, 0));
        }
        assertTrue(new File(temporary, "volumes-v5/l2/r.-2.-1.srg").isFile());
    }

    @Test
    void fingerprintMismatchInvalidatesWithoutDeleting() {
        LodVoxelSection section = new LodVoxelSection(0, 0, 0, 0,
                LodVoxelSection.Completeness.PROVISIONAL);
        try (LodVolumeStore writer = new LodVolumeStore(temporary, 1)) { writer.write(section); }
        try (LodVolumeStore reader = new LodVolumeStore(temporary, 2)) {
            assertNull(reader.read(0, 0, 0, 0));
        }
        assertTrue(new File(temporary, "volumes-v5/l0/r.0.0.srg").isFile());
    }

    @Test
    void compactProvisionalRootSurvivesWarmReopen() {
        LodVoxelSection section = new LodVoxelSection(7, 0, -9, 4,
                LodVoxelSection.Completeness.PROVISIONAL);
        long voxel = LodVoxel.pack(4, 15, 0, 0, 0, 255,
                LodVoxel.PROVENANCE_ANALYTIC, 12);
        for (int z = 0; z < 32; z++) for (int x = 0; x < 32; x++) {
            section.set(x, 3, z, voxel);
        }
        section.compact();
        long compactBytes = section.estimatedBytes();

        try (LodVolumeStore writer = new LodVolumeStore(temporary, 77)) { writer.write(section); }
        try (LodVolumeStore reader = new LodVolumeStore(temporary, 77)) {
            LodVoxelSection loaded = reader.read(7, 0, -9, 4);
            assertNotNull(loaded);
            assertEquals(LodVoxelSection.Completeness.PROVISIONAL, loaded.completeness());
            assertEquals(voxel, loaded.get(12, 3, 17));
            assertEquals(0L, loaded.get(12, 4, 17));
            assertEquals(compactBytes, loaded.estimatedBytes());
        }
    }
}
