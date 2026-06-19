package de.skyengine.game.entity;

import de.skyengine.core.input.Input;
import de.skyengine.game.physics.AABB;
import de.skyengine.game.world.World;
import de.skyengine.utils.math.MathUtils;
import org.lwjgl.glfw.GLFW;

public class EntityPlayer extends Entity {

    /* --- Augenhöhe --- */
    private static final float EYE_HEIGHT_STANDING = 1.62F;
    private static final float EYE_HEIGHT_SNEAKING = 1.27F;
    private static final float MOUSE_SENSITIVITY = 0.12F;

    /* --- Minecraft-Physik, alle Werte pro Tick (20 TPS) --- */
    private static final double GRAVITY = 0.08;
    private static final double JUMP_POWER = 0.42;
    private static final double SPRINT_JUMP_BOOST = 0.13;

    private static final double WALK_ACCEL = 0.1;
    private static final double AIR_ACCEL = 0.02;
    private static final double SPRINT_FACTOR = 1.5;
    private static final double STRAFE_FACTOR = 1.2;
    private static final double SNEAK_FACTOR = 0.3;

    private static final double GROUND_FRICTION = 0.546;
    private static final double AIR_DRAG_HORIZONTAL = 0.91;
    private static final double AIR_DRAG_VERTICAL = 0.98;

    private static final double FLY_ACCEL = 0.065;
    private static final double FLY_SPRINT_FACTOR = 1.8;
    private static final double FLY_VERTICAL_FACTOR = 0.6;   // Hoch/Runter langsamer als Vorwärts
    private static final double FLY_DRAG = 0.88;
    private static final double FLY_DRAG_Y = 0.6;

    /* --- Sneak-Kantenschutz --- */
    private static final double SNEAK_EDGE_STEP = 0.05;      // Schrittweite beim Kürzen der Bewegung
    private static final double SNEAK_EDGE_DROP = 0.6;       // ab dieser Falltiefe gilt "keine Kante mehr"

    private boolean flying = false; // Start im Fly-Modus, bis Spawn-Logik existiert
    private boolean sprinting = false;
    private boolean sneaking = false;
    private boolean noClip = false;

    /* Augenhöhe wird pro Tick Richtung Zielwert interpoliert (weiche Kamera beim Sneaken) */
    private float eyeHeight = EYE_HEIGHT_STANDING;
    private float lastEyeHeight = EYE_HEIGHT_STANDING;

    public EntityPlayer() {
        this.setSize(0.6F, 1.8F);
        this.stepHeight = 0.6; // halbe Slabs/Stufen automatisch hochlaufen (wie Minecraft)
    }

    /**
     * Per-TICK movement (20 TPS). Deterministic - only reads the frozen input state.
     */
    public void update(Input input, World world) {
        super.update();

        double forward = 0, strafe = 0;
        if (input.isKeyDown(GLFW.GLFW_KEY_W)) forward += 1;
        if (input.isKeyDown(GLFW.GLFW_KEY_S)) forward -= 1;
        if (input.isKeyDown(GLFW.GLFW_KEY_D)) strafe += 1;
        if (input.isKeyDown(GLFW.GLFW_KEY_A)) strafe -= 1;

        boolean up = input.isKeyDown(GLFW.GLFW_KEY_SPACE);
        boolean shift = input.isKeyDown(GLFW.GLFW_KEY_LEFT_SHIFT);

        /* Shift = Sneak nur am Boden-Modus; im Fly-Modus bleibt Shift "runter" */
        this.sneaking = !this.flying && shift;

        /* Sprint nur bei Vorwärtsbewegung und nicht beim Sneaken */
        this.sprinting = input.isKeyDown(GLFW.GLFW_KEY_LEFT_CONTROL) && forward > 0 && !this.sneaking;

        /* Augenhöhe weich Richtung Ziel bewegen (~3 Ticks Übergang) */
        this.lastEyeHeight = this.eyeHeight;
        float targetEye = this.sneaking ? EYE_HEIGHT_SNEAKING : EYE_HEIGHT_STANDING;
        this.eyeHeight += (targetEye - this.eyeHeight) * 0.5F;

        if (this.sneaking) {
            forward *= SNEAK_FACTOR;
            strafe *= SNEAK_FACTOR;
        }

        if (this.flying) {
            this.travelFlying(world, forward, strafe, up, shift);
        } else {
            this.travelWalking(world, forward, strafe, up);
        }
    }

    /**
     * Normales Movement: Gravitation, Springen, Boden-/Luftreibung, Sneak-Kantenschutz.
     * Reihenfolge wie Minecraft: Beschleunigen -> Bewegen -> Reibung & Gravitation.
     */
    private void travelWalking(World world, double forward, double strafe, boolean jump) {
        if (jump && this.onGround) {
            this.motionY = JUMP_POWER;
            if (this.sprinting) {
                double yawRad = Math.toRadians(this.yaw);
                this.motionX += Math.sin(yawRad) * SPRINT_JUMP_BOOST;
                this.motionZ += -Math.cos(yawRad) * SPRINT_JUMP_BOOST;
            }
        }

        double accel = (this.onGround ? WALK_ACCEL : AIR_ACCEL) * (this.sprinting ? SPRINT_FACTOR : 1.0);
        this.moveRelative(strafe, forward, accel);

        double dx = this.motionX;
        double dy = this.motionY;
        double dz = this.motionZ;

        /* Kantenschutz: beim Sneaken am Boden die Bewegung so kürzen,
           dass die BoundingBox nie komplett über dem Abgrund hängt */
        if (this.sneaking && this.onGround && dy <= 0) {
            double[] adjusted = this.backOffFromEdge(world, dx, dz);
            dx = adjusted[0];
            dz = adjusted[1];
        }

        this.move(world, dx, dy, dz);

        /* Gravitation & Reibung NACH dem Bewegen */
        this.motionY -= GRAVITY;
        this.motionY *= AIR_DRAG_VERTICAL;

        double friction = this.onGround ? GROUND_FRICTION : AIR_DRAG_HORIZONTAL;
        this.motionX *= friction;
        this.motionZ *= friction;
    }

    /**
     * Creative-Fly: keine Gravitation, Space/Shift für hoch/runter,
     * Sprint verdoppelt die Geschwindigkeit.
     */
    private void travelFlying(World world, double forward, double strafe, boolean up, boolean down) {
        double accel = FLY_ACCEL * (this.sprinting ? FLY_SPRINT_FACTOR : 1.0);
        this.moveRelative(strafe, forward, accel);

        /* Horizontale Endgeschwindigkeit (accel / (1 - drag)), vertikal davon ein Anteil. */
        double verticalSpeed = (FLY_ACCEL / (1.0 - FLY_DRAG)) * (this.sprinting ? FLY_SPRINT_FACTOR : 1.0) * FLY_VERTICAL_FACTOR;

        if (up && !down) {
            this.motionY = verticalSpeed;
        } else if (down && !up) {
            this.motionY = -verticalSpeed;
        }

        this.move(world, this.motionX, this.motionY, this.motionZ);

        this.motionX *= FLY_DRAG;
        this.motionY *= FLY_DRAG_Y;
        this.motionZ *= FLY_DRAG;
    }

    /**
     * Wandelt Strafe/Forward-Input in eine Beschleunigung relativ zum Yaw um.
     * Diagonale wird normalisiert, danach wird der STRAFE_FACTOR angewendet.
     */
    private void moveRelative(double strafe, double forward, double accel) {
        double lenSq = strafe * strafe + forward * forward;
        if (lenSq < 1.0E-8) return;

        double len = Math.sqrt(lenSq);
        if (len < 1.0) len = 1.0;

        strafe = strafe / len * accel * STRAFE_FACTOR;
        forward = forward / len * accel;

        double yawRad = Math.toRadians(this.yaw);
        double sin = Math.sin(yawRad);
        double cos = Math.cos(yawRad);

        this.motionX += sin * forward + cos * strafe;
        this.motionZ += -cos * forward + sin * strafe;
    }

    /**
     * Minecraft-Kantenschutz: kürzt dx/dz in 0.05er-Schritten Richtung 0,
     * solange unterhalb der Zielposition (um SNEAK_EDGE_DROP versetzt)
     * KEIN Boden mehr wäre. Erst je Achse einzeln, dann beide kombiniert
     * (für diagonales Sneaken an Ecken).
     */
    private double[] backOffFromEdge(World world, double dx, double dz) {
        double x = dx, z = dz;

        while (x != 0 && this.noGroundUnder(world, x, 0)) {
            x = shrinkTowardsZero(x);
        }
        while (z != 0 && this.noGroundUnder(world, 0, z)) {
            z = shrinkTowardsZero(z);
        }
        while (x != 0 && z != 0 && this.noGroundUnder(world, x, z)) {
            x = shrinkTowardsZero(x);
            z = shrinkTowardsZero(z);
        }

        return new double[]{x, z};
    }

    /**
     * true, wenn unter der um (dx, dz) versetzten und um SNEAK_EDGE_DROP abgesenkten
     * Box KEINE Kollisionsbox tatsächlich liegt.
     *
     * <p>Wichtig: {@link World#getCollisionBoxes} ist nur eine Broadphase und meldet
     * auch Boxen benachbarter Voxel. Bei schmalen Blöcken (Zaun-Pfosten, Glasscheibe,
     * Eisenstäbe), die schmaler als ihr Voxel sind, würde ein reiner {@code isEmpty()}-
     * Test fälschlich "Boden vorhanden" liefern, sobald man seitlich vom Pfosten steht –
     * man liefe beim Sneaken herunter. Darum hier ein präziser Schnitt-Test.
     */
    private boolean noGroundUnder(World world, double dx, double dz) {
        AABB probe = this.boundingBox.copy().move(dx, -SNEAK_EDGE_DROP, dz);
        for (AABB box : world.getCollisionBoxes(probe)) {
            if (box.intersects(probe)) return false;
        }
        return true;
    }

    private static double shrinkTowardsZero(double value) {
        if (value < SNEAK_EDGE_STEP && value > -SNEAK_EDGE_STEP) return 0;
        return value > 0 ? value - SNEAK_EDGE_STEP : value + SNEAK_EDGE_STEP;
    }

    /**
     * Friert die Tick-Interpolation ein (prev = current), z.B. bei offenem GUI. Ohne das würde
     * {@code Camera.follow} weiter zwischen zwei verschiedenen Tick-Positionen interpolieren und die
     * Kamera oszillieren ("jittern"), wenn man die Truhe beim Laufen/Springen öffnet.
     */
    public void snapPrevToCurrent() {
        this.lastX = this.x;
        this.lastY = this.y;
        this.lastZ = this.z;
        this.lastEyeHeight = this.eyeHeight;
    }

    /**
     * Per-FRAME mouse look - applied outside the tick so aiming stays smooth at any FPS.
     */
    public void turn(double deltaX, double deltaY) {
        this.yaw += (float) (deltaX * MOUSE_SENSITIVITY);
        this.pitch += (float) (deltaY * MOUSE_SENSITIVITY);
        this.pitch = MathUtils.clamp(this.pitch, -89.9F, 89.9F);

        if (this.yaw > 360) this.yaw -= 360;
        if (this.yaw < 0) this.yaw += 360;
    }

    public void toggleFlying() {
        this.flying = !this.flying;
        if (this.flying) {
            /* Beim Einschalten Fallgeschwindigkeit abfangen, sonst "fällt" man weiter */
            this.motionY = 0;
        } else {
            /* NoClip nur im Flugmodus - beim Landen abschalten, sonst fällt man durch Blöcke */
            this.noClip = false;
        }
    }

    /** NoClip umschalten - nur im Flugmodus aktivierbar. */
    public void toggleNoClip() {
        if (!this.flying) return;
        this.noClip = !this.noClip;
    }

    public boolean isFlying() {
        return flying;
    }

    public boolean isSprinting() {
        return sprinting;
    }

    public boolean isSneaking() {
        return sneaking;
    }

    public boolean isNoClip() {
        return noClip;
    }

    /**
     * Interpolierte Augenhöhe für die Kamera (weicher Sneak-Übergang).
     */
    public float getEyeHeight(float partialTick) {
        return this.lastEyeHeight + (this.eyeHeight - this.lastEyeHeight) * partialTick;
    }
}