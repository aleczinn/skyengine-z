package de.skyengine.graphics.camera;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class ZoomControllerTest {

    private static final float EPSILON = 0.0001F;

    @Test
    void reachesSmoothMidpointAndFullZoomAfterConfiguredDuration() {
        ZoomController zoom = new ZoomController();
        zoom.update(false, 0L);

        zoom.update(true, ZoomController.DURATION_NANOS / 4);
        assertEquals(0.25F, zoom.progress(), EPSILON);
        assertEquals(70.625F, zoom.fov(80F, 4F), EPSILON);

        zoom.update(true, ZoomController.DURATION_NANOS / 2);
        assertEquals(0.5F, zoom.progress(), EPSILON);
        assertEquals(46.875F, zoom.fov(75F, 4F), EPSILON);
        assertEquals(0.625F, zoom.sensitivityScale(4F), EPSILON);

        zoom.update(true, ZoomController.DURATION_NANOS);
        assertEquals(1F, zoom.progress(), EPSILON);
        assertEquals(18.75F, zoom.fov(75F, 4F), EPSILON);
        assertEquals(0.25F, zoom.sensitivityScale(4F), EPSILON);
    }

    @Test
    void releasingOrReversingMidAnimationNeverJumps() {
        ZoomController zoom = new ZoomController();
        zoom.update(false, 0L);
        zoom.update(true, 90_000_000L);
        float midpointFov = zoom.fov(80F, 4F);

        zoom.update(false, 135_000_000L);
        assertEquals(0.25F, zoom.progress(), EPSILON);
        zoom.update(true, 180_000_000L);

        assertEquals(0.5F, zoom.progress(), EPSILON);
        assertEquals(midpointFov, zoom.fov(80F, 4F), EPSILON);
    }

    @Test
    void resetRestoresBaseFovAndFullSensitivity() {
        ZoomController zoom = new ZoomController();
        zoom.update(false, 0L);
        zoom.update(true, ZoomController.DURATION_NANOS);
        zoom.reset();

        assertEquals(75F, zoom.fov(75F, 10F), EPSILON);
        assertEquals(1F, zoom.sensitivityScale(10F), EPSILON);
        assertEquals(0F, zoom.progress(), EPSILON);
    }
}
