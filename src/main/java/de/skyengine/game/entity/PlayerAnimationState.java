package de.skyengine.game.entity;

/**
 * Prozeduraler Animations-Zustand des Spielers (MC-Formeln): Gliedmaßen-Schwung aus der
 * Bewegung, weich nachziehender Körper-Yaw, Arm-Schwung beim Abbauen/Platzieren, Hurt-Timer
 * fürs Kamerawackeln und Bob/WalkDist fürs View-Bobbing. Getickt im GameContainer (20 TPS),
 * im Renderer mit partialTick interpoliert. Bewusst außerhalb von {@link EntityPlayer}
 * (reine Darstellung, kein Gameplay — Savegames bleiben unberührt).
 */
public final class PlayerAnimationState {

    private static final int SWING_TICKS = 6;   // Dauer eines Arm-Schwungs (wie MC)
    private static final int HURT_TICKS = 10;   // Abklingzeit des Hurt-Kamera-Rolls (wie MC)

    /* Gliedmaßen-Schwung: Phase (akkumuliert) + Amplitude 0..1 (geglättet). */
    private float limbSwing;
    private float limbSwingAmount, prevLimbSwingAmount;

    /* Körper-Yaw zieht dem Kopf weich nach (MC renderYawOffset), Grad. */
    private float bodyYaw, prevBodyYaw;

    /* Arm-Schwung 0..1 (interpolierbar über prev). */
    private boolean swinging;
    private int swingTime;
    private float swingProgress, prevSwingProgress;

    /* Hurt-Timer (dekrementiert pro Tick). */
    private int hurtTime;

    /* View-Bobbing (MC bob/walkDist). */
    private float bob, prevBob;
    private float walkDist, prevWalkDist;

    private boolean initialized;

    /** Ein Simulations-Tick (20 TPS) — nach {@code player.update(...)} aufrufen. */
    public void tick(EntityPlayer player) {
        this.prevLimbSwingAmount = this.limbSwingAmount;
        this.prevBodyYaw = this.bodyYaw;
        this.prevSwingProgress = this.swingProgress;
        this.prevBob = this.bob;
        this.prevWalkDist = this.walkDist;

        if (!this.initialized) {
            this.bodyYaw = player.yaw;
            this.prevBodyYaw = player.yaw;
            this.initialized = true;
        }

        double dx = player.x - player.lastX;
        double dz = player.z - player.lastZ;
        float horizontal = (float) Math.sqrt(dx * dx + dz * dz);

        float amount = Math.min(horizontal * 4F, 1F);
        this.limbSwingAmount += (amount - this.limbSwingAmount) * 0.4F;
        this.limbSwing += this.limbSwingAmount;

        /* Körper folgt dem Blick: bei Bewegung aktiv nachziehen, im Stand erst wenn der Kopf
           mehr als 75° verdreht ist; danach harter Clamp auf ±75° (MC-Verhalten vereinfacht). */
        float diff = wrapDegrees(player.yaw - this.bodyYaw);
        if (horizontal > 0.005F) {
            this.bodyYaw += diff * 0.3F;
        }
        diff = wrapDegrees(player.yaw - this.bodyYaw);
        if (diff < -75F) this.bodyYaw = player.yaw + 75F;
        if (diff > 75F) this.bodyYaw = player.yaw - 75F;

        /* Vanilla updateSwingTime: Neustart-Marker -1 wird HIER inkrementiert (nicht im
           swing()-Aufruf) — kein sichtbarer Mid-Frame-Reset des Fortschritts. */
        if (this.swinging) {
            if (++this.swingTime >= SWING_TICKS) {
                this.swingTime = 0;
                this.swinging = false;
            }
        } else {
            this.swingTime = 0;
        }
        this.swingProgress = Math.max(this.swingTime, 0) / (float) SWING_TICKS;

        if (this.hurtTime > 0) this.hurtTime--;

        /* Bob-Amplitude wächst nur am Boden (Vanilla) — in der Luft klingt sie in ~3 Ticks ab. */
        float bobTarget = player.onGround ? Math.min(0.1F, horizontal) : 0F;
        this.bob += (bobTarget - this.bob) * 0.4F;
        this.walkDist += horizontal * 0.6F;
    }

    /** Markiert einen Arm-Schwung-(Neu-)Start (MC-Guard: erst ab der Hälfte); der Tick führt ihn aus. */
    public void swing() {
        if (!this.swinging || this.swingTime >= SWING_TICKS / 2 || this.swingTime < 0) {
            this.swingTime = -1;
            this.swinging = true;
        }
    }

    /** Startet den Hurt-Timer (Kamera-Roll beim Schaden). */
    public void hurt() {
        this.hurtTime = HURT_TICKS;
    }

    /** Bei Pause/GUI-Standbild: prev = current, damit nichts weiter-interpoliert. */
    public void snapPrev() {
        this.prevLimbSwingAmount = this.limbSwingAmount;
        this.prevBodyYaw = this.bodyYaw;
        this.prevSwingProgress = this.swingProgress;
        this.prevBob = this.bob;
        this.prevWalkDist = this.walkDist;
    }

    /** Kompletter Reset (Welt-Eintritt/Respawn). */
    public void reset() {
        this.limbSwing = 0;
        this.limbSwingAmount = 0; this.prevLimbSwingAmount = 0;
        this.bodyYaw = 0; this.prevBodyYaw = 0;
        this.swinging = false; this.swingTime = 0;
        this.swingProgress = 0; this.prevSwingProgress = 0;
        this.hurtTime = 0;
        this.bob = 0; this.prevBob = 0;
        this.walkDist = 0; this.prevWalkDist = 0;
        this.initialized = false;
    }

    /* --- Interpolations-Getter (partialTick) --- */

    public float getLimbSwing(float partialTick) {
        /* MC: Phase minus des noch nicht getickten Anteils der Amplitude. */
        return this.limbSwing - this.limbSwingAmount * (1F - partialTick);
    }

    public float getLimbSwingAmount(float partialTick) {
        return this.prevLimbSwingAmount + (this.limbSwingAmount - this.prevLimbSwingAmount) * partialTick;
    }

    public float getBodyYaw(float partialTick) {
        return this.prevBodyYaw + wrapDegrees(this.bodyYaw - this.prevBodyYaw) * partialTick;
    }

    public float getSwingProgress(float partialTick) {
        float f = this.swingProgress - this.prevSwingProgress;
        if (f < 0F) f += 1F;   // Schwung-Ende: 5/6 -> 0 läuft vorwärts weiter statt rückwärts
        return this.prevSwingProgress + f * partialTick;
    }

    public int getHurtTime() {
        return this.hurtTime;
    }

    public float getBob(float partialTick) {
        return this.prevBob + (this.bob - this.prevBob) * partialTick;
    }

    /** Vanilla-bobView-Phase: extrapoliert ÜBER den aktuellen Tick hinaus (walkDist + Δ·pt). */
    public float getWalkDistExtrapolated(float partialTick) {
        return this.walkDist + (this.walkDist - this.prevWalkDist) * partialTick;
    }

    /** Winkel nach [-180, 180) wickeln. */
    public static float wrapDegrees(float degrees) {
        float d = degrees % 360F;
        if (d >= 180F) d -= 360F;
        if (d < -180F) d += 360F;
        return d;
    }
}
