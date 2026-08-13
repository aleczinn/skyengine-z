package de.skyengine.game;

/**
 * Tickbasierter Minecraft-26.2-Verlauf der Unterwasser-Sicht. Die ersten fünf Sekunden
 * liefern 60 %, die folgenden 25 Sekunden die restlichen 40 %. Außerhalb des Wassers baut
 * sich der gespeicherte Wert zehnmal schneller ab, damit kurzes Auftauchen nicht bei null startet.
 */
final class WaterVision {

    static final int MAX_TICKS = 600;
    private int ticks;

    void tick(boolean underwater) {
        this.ticks = Math.clamp(this.ticks + (underwater ? 1 : -10), 0, MAX_TICKS);
    }

    void reset() {
        this.ticks = 0;
    }

    float factor() {
        return factor(this.ticks);
    }

    int ticks() {
        return this.ticks;
    }

    static float factor(int ticks) {
        if (ticks >= MAX_TICKS) return 1F;
        float first = Math.clamp(ticks / 100F, 0F, 1F);
        float second = Math.clamp((ticks - 100F) / 500F, 0F, 1F);
        return first * 0.6F + second * 0.4F;
    }
}
