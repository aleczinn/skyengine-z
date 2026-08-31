package de.skyengine.graphics.camera;

/**
 * FPS-unabhängiger Zustand des gehaltenen Kamera-Zooms. Die lineare Zeitachse wird erst beim
 * Auslesen mit Smoothstep geglättet, damit Ziel-FOV und Maus-Sensitivität exakt dieselbe
 * Animation verwenden. Zeitstempel werden injiziert, damit die Logik ohne Renderkontext
 * deterministisch testbar bleibt.
 */
public final class ZoomController {

    public static final long DURATION_NANOS = 180_000_000L;

    private float progress;
    private long lastUpdateNanos = Long.MIN_VALUE;

    /** Bewegt den Zoomzustand zeitbasiert zum angeforderten Ziel. */
    public void update(boolean active, long nowNanos) {
        if (this.lastUpdateNanos == Long.MIN_VALUE) {
            this.lastUpdateNanos = nowNanos;
            return;
        }

        long elapsed = Math.max(0L, nowNanos - this.lastUpdateNanos);
        this.lastUpdateNanos = nowNanos;
        float delta = (float) ((double) elapsed / DURATION_NANOS);
        this.progress = Math.clamp(this.progress + (active ? delta : -delta), 0F, 1F);
    }

    /** Setzt Zustand und Zeitbasis zurück, beispielsweise beim Verlassen einer Welt. */
    public void reset() {
        this.progress = 0F;
        this.lastUpdateNanos = Long.MIN_VALUE;
    }

    /** Aktuelles animiertes Kamera-FOV. Voller Zoom entspricht {@code baseFov / factor}. */
    public float fov(float baseFov, float factor) {
        float eased = this.easedProgress();
        return baseFov + (baseFov / factor - baseFov) * eased;
    }

    /** Faktor für die Maus-Sensitivität, passend zum aktuell sichtbaren FOV. */
    public float sensitivityScale(float factor) {
        return 1F + (1F / factor - 1F) * this.easedProgress();
    }

    /** Nur für Diagnose und deterministische Tests. */
    public float progress() {
        return this.progress;
    }

    private float easedProgress() {
        return this.progress * this.progress * (3F - 2F * this.progress);
    }
}
