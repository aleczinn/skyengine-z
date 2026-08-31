package de.skyengine.graphics.gui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class DebugOverlayLayoutTest {
    @Test
    void workerPanelFitsBetweenDebugHeaderAndGraphsAtSupportedWidths() {
        assertLayout(340, 84, 203);
        assertLayout(512, 84, 243);
        assertLayout(1024, 84, 501);
    }

    private static void assertLayout(float width, float top, float graphY) {
        DebugOverlay.PanelRect[] panels = DebugOverlay.workerPanelLayout(width, top, graphY);
        assertEquals(2, panels.length);
        for (DebugOverlay.PanelRect panel : panels) {
            assertTrue(panel.x() >= 0);
            assertTrue(panel.y() >= top);
            assertTrue(panel.x() + panel.width() <= width);
            assertTrue(panel.y() + panel.height() <= graphY);
        }
        assertTrue(panels[0].x() + panels[0].width() <= panels[1].x());
    }
}
