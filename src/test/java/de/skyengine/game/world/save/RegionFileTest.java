package de.skyengine.game.world.save;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class RegionFileTest {

    @TempDir
    Path tempDir;

    @Test
    void payloadsSurviveCloseAndReopenWithoutLeavingTheTempDirectory() throws Exception {
        Path path = this.tempDir.resolve("r.0.-1.srg");
        byte[] first = payload(19_000, 17);
        byte[] second = payload(2_000, 91);

        try (RegionFile region = new RegionFile(path.toFile())) {
            assertFalse(region.has(3, 9));
            assertNull(region.read(3, 9));
            region.write(3, 9, first);
            region.write(4, 9, second);
            assertTrue(region.has(3, 9));
        }

        try (RegionFile region = new RegionFile(path.toFile())) {
            assertArrayEquals(first, region.read(3, 9));
            assertArrayEquals(second, region.read(4, 9));
            assertNull(region.read(5, 9));
        }

        assertTrue(path.startsWith(this.tempDir));
    }

    private static byte[] payload(int size, int salt) {
        byte[] bytes = new byte[size];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) (i * 31 + salt);
        }
        return bytes;
    }
}
