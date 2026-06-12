package de.skyengine.game.entity;

import de.skyengine.core.input.Input;
import de.skyengine.game.world.World;
import de.skyengine.utils.math.MathUtils;
import org.lwjgl.glfw.GLFW;

public class EntityPlayer extends Entity {

    private static final float EYE_HEIGHT = 1.62F;
    private static final float MOUSE_SENSITIVITY = 0.12F;

    /* --- Minecraft-Physik, alle Werte pro Tick (20 TPS) --- */
    private static final double GRAVITY = 0.08;
    private static final double JUMP_POWER = 0.42;          // ~1.25 Blöcke Sprunghöhe
    private static final double SPRINT_JUMP_BOOST = 0.2;    // Schub nach vorn beim Sprint-Sprung

    private static final double WALK_ACCEL = 0.1;           // ergibt ~4.3 m/s
    private static final double AIR_ACCEL = 0.02;
    private static final double SPRINT_FACTOR = 1.3;        // ergibt ~5.6 m/s

    private static final double GROUND_FRICTION = 0.546;    // slipperiness(0.6) * 0.91
    private static final double AIR_DRAG_HORIZONTAL = 0.91;
    private static final double AIR_DRAG_VERTICAL = 0.98;

    private static final double FLY_ACCEL = 0.05;           // ergibt ~10.9 m/s (MC Creative)
    private static final double FLY_SPRINT_FACTOR = 2.0;    // ergibt ~21.8 m/s
    private static final double FLY_DRAG = 0.91;

    private boolean flying = true; // Start im Fly-Modus, bis Spawn-Logik existiert
    private boolean sprinting = false;

    public EntityPlayer() {
        this.setSize(0.6F, 1.8F);
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
        boolean down = input.isKeyDown(GLFW.GLFW_KEY_LEFT_SHIFT);

        /* Sprint nur bei Vorwärtsbewegung, wie in Minecraft */
        this.sprinting = input.isKeyDown(GLFW.GLFW_KEY_LEFT_CONTROL) && forward > 0;

        if (this.flying) {
            this.travelFlying(world, forward, strafe, up, down);
        } else {
            this.travelWalking(world, forward, strafe, up);
        }
    }

    /**
     * Normales Movement: Gravitation, Springen, Boden-/Luftreibung.
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

        this.move(world, this.motionX, this.motionY, this.motionZ);

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

        if (up) this.motionY += accel;
        if (down) this.motionY -= accel;

        this.move(world, this.motionX, this.motionY, this.motionZ);

        this.motionX *= FLY_DRAG;
        this.motionY *= FLY_DRAG;
        this.motionZ *= FLY_DRAG;
    }

    /**
     * Wandelt Strafe/Forward-Input in eine Beschleunigung relativ zum Yaw um.
     * Diagonale wird normalisiert.
     */
    private void moveRelative(double strafe, double forward, double accel) {
        double lenSq = strafe * strafe + forward * forward;
        if (lenSq < 1.0E-8) return;

        double len = Math.sqrt(lenSq);
        if (len < 1.0) len = 1.0;

        strafe = strafe / len * accel;
        forward = forward / len * accel;

        double yawRad = Math.toRadians(this.yaw);
        double sin = Math.sin(yawRad);
        double cos = Math.cos(yawRad);

        this.motionX += sin * forward + cos * strafe;
        this.motionZ += -cos * forward + sin * strafe;
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
        }
    }

    public boolean isFlying() {
        return flying;
    }

    public boolean isSprinting() {
        return sprinting;
    }

    public float getEyeHeight() {
        return EYE_HEIGHT;
    }
}