package de.skyengine.game.world.debug;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class SimulationTelemetryTest {

    @Test
    void disabledTelemetryIsANoOpAndDoesNotStartATimer() {
        SimulationTelemetry telemetry = new SimulationTelemetry();

        assertEquals(0, telemetry.beginRedstoneTiming());
        telemetry.beginTick();
        telemetry.recordWireWake();
        telemetry.recordScheduledRequest(true);
        telemetry.recordDeferredDropped();
        telemetry.endTick();

        assertEquals(new SimulationTelemetry().snapshot(), telemetry.snapshot());
        assertNull(telemetry.statusLineAndReset());
    }

    @Test
    void aggregatesOneWindowAndResetsAfterFormatting() {
        SimulationTelemetry telemetry = new SimulationTelemetry();
        telemetry.setEnabled(true);
        telemetry.beginTick();
        telemetry.recordWireWake();
        telemetry.recordSuppressedWireWake();
        telemetry.recordWireSolve(System.nanoTime() - 10_000, 12, 64, -8,
                300, 25, 71, true);
        telemetry.recordScheduledRequest(true);
        telemetry.recordScheduledRequest(false);
        telemetry.recordScheduledDue();
        telemetry.recordScheduledExecuted();
        telemetry.recordBlockEventWave(7);
        telemetry.recordBlockEventBudgetHit();
        telemetry.recordDeferredProcessed(5);
        telemetry.recordDeferredRequeued();
        telemetry.recordDeferredDropped();
        telemetry.endTick();

        SimulationTelemetry.Snapshot snapshot = telemetry.snapshot();
        assertEquals(1, snapshot.ticks());
        assertEquals(300, snapshot.wireCells());
        assertEquals(25, snapshot.wireWrites());
        assertEquals(71, snapshot.wireReceivers());
        assertEquals(1, snapshot.cappedWireSolves());
        assertEquals(12, snapshot.largestWireX());
        assertEquals(-8, snapshot.largestWireZ());
        assertEquals(1, snapshot.scheduledAccepted());
        assertEquals(1, snapshot.scheduledDeduplicated());
        assertEquals(7, snapshot.blockEventsProcessed());
        assertEquals(1, snapshot.blockEventBudgetHits());
        assertEquals(1, snapshot.deferredDropped());

        String line = telemetry.statusLineAndReset();
        assertNotNull(line);
        assertTrue(line.contains("largest=300@12,64,-8"));
        assertTrue(line.contains("sched accepted=1 dedup=1 due=1 maxDuePerTick=1 run=1"));
        assertTrue(line.contains("events waves=1 processed=7 waveLimit=0 budget=1"));
        assertTrue(line.contains("deferred processed=5 requeued=1 dropped=1"));
        assertEquals(0, telemetry.snapshot().ticks());
        assertTrue(telemetry.isEnabled());
    }
}
