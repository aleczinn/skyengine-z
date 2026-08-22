package de.skyengine.graphics.gui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class DebugOverlayLayoutTest {
    @Test
    void sixPanelsFitBetweenDebugHeaderAndGraphsAtSupportedWidths() {
        assertLayout(340, 84, 203);
        assertLayout(512, 84, 243);
        assertLayout(1024, 84, 501);
    }

    private static void assertLayout(float width, float top, float graphY) {
        DebugOverlay.PanelRect[] panels = DebugOverlay.workerPanelLayout(width, top, graphY);
        assertEquals(6, panels.length);
        for (DebugOverlay.PanelRect panel : panels) {
            assertTrue(panel.x() >= 0);
            assertTrue(panel.y() >= top);
            assertTrue(panel.x() + panel.width() <= width);
            assertTrue(panel.y() + panel.height() <= graphY);
        }
        assertEquals(panels[0].x(), panels[3].x());
        assertEquals(panels[0].width(), panels[5].width());
        assertTrue(panels[3].y() > panels[0].y());
    }
}
