package de.skyengine.audio;

import java.util.Random;

/**
 * Tickbasierte Unterwasser-Audiologik nach Minecraft 26.2. Zustandsflanken hängen am Auge,
 * Splash/Schwimmen am Körperkontakt; dadurch bleibt der Render-partialTick ohne Doppelevents.
 */
public final class UnderwaterAudioController {

    public static final int ADDITION_NORMAL = 0;
    public static final int ADDITION_RARE = 1;
    public static final int ADDITION_ULTRA_RARE = 2;

    private static final double SWIM_INTERVAL = 1.0;
    private static final double MOVEMENT_EMISSION_SCALE = 0.6;

    private final UnderwaterAudioSink sink;
    private final Random random;
    private boolean eyesUnderwater;
    private boolean bodyInWater;
    private boolean initialized;
    private int loopFade;
    private double swimDistance;
    private double nextSwimSound = SWIM_INTERVAL;

    public UnderwaterAudioController(SoundManager sounds) {
        this(sounds, new Random());
    }

    UnderwaterAudioController(UnderwaterAudioSink sink, Random random) {
        this.sink = sink;
        this.random = random;
    }

    public void tick(boolean eyesUnderwater, boolean bodyInWater, boolean emitSwimSounds,
                     double dx, double dy, double dz) {
        if (!this.eyesUnderwater && eyesUnderwater) this.sink.playUnderwaterEnter();
        if (this.eyesUnderwater && !eyesUnderwater) this.sink.playUnderwaterExit();

        if (eyesUnderwater) {
            this.loopFade = Math.min(40, this.loopFade + 1);
            float chance = this.random.nextFloat();
            if (chance < 0.0001F) this.sink.playUnderwaterAddition(ADDITION_ULTRA_RARE);
            else if (chance < 0.001F) this.sink.playUnderwaterAddition(ADDITION_RARE);
            else if (chance < 0.01F) this.sink.playUnderwaterAddition(ADDITION_NORMAL);
        } else {
            this.loopFade = Math.max(0, this.loopFade - 2);
        }
        this.sink.setUnderwaterLoopGain(this.loopFade / 40F);

        double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
        float speed = (float) distance;
        if (this.initialized && !this.bodyInWater && bodyInWater) this.sink.playSplash(speed);
        if (bodyInWater && emitSwimSounds) {
            /* Entity.applyMovementEmissionAndPlaySound: Schritte/Schwimmen zaehlen nur die
               horizontale Strecke und skalieren sie mit 0,6. Beim Ueberspringen mehrerer
               Schwellen wird trotzdem nur EIN Sound erzeugt und die naechste Schwelle auf
               floor(moveDist)+1 gesetzt; so kann hohe Geschwindigkeit nichts spammen. */
            this.swimDistance += Math.sqrt(dx * dx + dz * dz) * MOVEMENT_EMISSION_SCALE;
            if (this.swimDistance > this.nextSwimSound) {
                this.nextSwimSound = Math.floor(this.swimDistance) + SWIM_INTERVAL;
                float volume = (float) Math.min(1.0,
                        Math.sqrt(dx * dx * 0.2 + dy * dy + dz * dz * 0.2) * 0.35);
                this.sink.playSwim(volume);
            }
        } else {
            this.resetSwimCadence();
        }

        this.eyesUnderwater = eyesUnderwater;
        this.bodyInWater = bodyInWater;
        this.initialized = true;
    }

    /** Weltwechsel/Shutdown ohne künstlichen Exit-Sound. */
    public void reset() {
        this.eyesUnderwater = false;
        this.bodyInWater = false;
        this.initialized = false;
        this.loopFade = 0;
        this.resetSwimCadence();
        this.sink.setUnderwaterLoopGain(0F);
    }

    private void resetSwimCadence() {
        this.swimDistance = 0;
        this.nextSwimSound = SWIM_INTERVAL;
    }
}
