package de.skyengine.game.entity;

import de.skyengine.core.input.Input;
import de.skyengine.core.io.IUpdatable;
import de.skyengine.utils.math.MathUtils;
import org.lwjgl.glfw.GLFW;

public class EntityPlayer extends Entity implements IUpdatable {

    private static final float EYE_HEIGHT = 1.62F;
    private static final double FLY_SPEED = 0.5;     // blocks per tick (= 10 blocks/s at 20 TPS)
    private static final double SPRINT_MULT = 3.0;
    private static final float MOUSE_SENSITIVITY = 0.12F;

    /**
     * Per-TICK movement (20 TPS). Deterministic - only reads the frozen input state.
     */
    @Override
    public void update(Input input) {
        super.tick();

        double forward = 0, strafe = 0, vertical = 0;
        if (input.isKeyDown(GLFW.GLFW_KEY_W)) forward += 1;
        if (input.isKeyDown(GLFW.GLFW_KEY_S)) forward -= 1;
        if (input.isKeyDown(GLFW.GLFW_KEY_D)) strafe += 1;
        if (input.isKeyDown(GLFW.GLFW_KEY_A)) strafe -= 1;
        if (input.isKeyDown(GLFW.GLFW_KEY_SPACE)) vertical += 1;
        if (input.isKeyDown(GLFW.GLFW_KEY_LEFT_SHIFT)) vertical -= 1;

        double speed = FLY_SPEED * (input.isKeyDown(GLFW.GLFW_KEY_LEFT_CONTROL) ? SPRINT_MULT : 1.0);

        /* Normalize diagonal movement */
        double len = Math.sqrt(forward * forward + strafe * strafe);
        if (len > 0) {
            forward /= len;
            strafe /= len;
        }

        double yawRad = Math.toRadians(this.yaw);
        this.x += (Math.sin(yawRad) * forward + Math.cos(yawRad) * strafe) * speed;
        this.z += (-Math.cos(yawRad) * forward + Math.sin(yawRad) * strafe) * speed;
        this.y += vertical * speed;
    }

    /**
     * Per-FRAME mouse look - applied outside the tick so aiming stays smooth at any FPS.
     */
    public void turn(double deltaX, double deltaY) {
        this.yaw += (float) (deltaX * MOUSE_SENSITIVITY);
        this.pitch += (float) (deltaY * MOUSE_SENSITIVITY);
        this.pitch = MathUtils.clamp(this.pitch, -89.9F, 89.9F);

        /* Keep yaw in a sane range */
        if (this.yaw > 360) this.yaw -= 360;
        if (this.yaw < 0) this.yaw += 360;
    }

    public float getEyeHeight() {
        return EYE_HEIGHT;
    }
}