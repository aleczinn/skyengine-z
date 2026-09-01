package de.skyengine.game.world.chunk.debug;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class MesherBenchmarkTest {
    @Test
    void timingSummaryUsesDeterministicMedianAndP95() {
        Map<String, Object> summary = MesherBenchmark.summarizeNanos(
                5_000_000L, 1_000_000L, 2_000_000L, 4_000_000L, 3_000_000L);

        assertEquals(5, summary.get("samples"));
        assertEquals(3.0, (double) summary.get("medianMs"));
        assertEquals(5.0, (double) summary.get("p95Ms"));
        assertEquals(5.0, (double) summary.get("maxMs"));
    }

    @Test
    void reportSerializationKeepsMachineReadableFields() {
        String json = MesherBenchmark.serializeReport(Map.of(
                "schemaVersion", 2,
                "scenarios", new Object[]{Map.of("name", "generated-ao", "sectionsPerSecond", 42.5)}));

        assertTrue(json.contains("\"schemaVersion\": 2"));
        assertTrue(json.contains("\"generated-ao\""));
        assertTrue(json.contains("\"sectionsPerSecond\": 42.5"));
    }

    @Test
    void nanoTimeCalibrationNeverReportsZero() {
        assertTrue(MesherBenchmark.calibrateNanoTimeOverhead() > 0);
    }

    @Test
    void sliceSamplingRotatesAcrossAllOffsets() {
        int[] sampled = new int[6 * 32];
        long cursor = 0;
        for (int section = 0; section < 16; section++) {
            for (int slice = 0; slice < sampled.length; slice++, cursor++) {
                if (MesherBenchmark.sampledSlice(cursor, 16)) sampled[slice]++;
            }
        }
        for (int count : sampled) assertEquals(1, count);
    }
}
