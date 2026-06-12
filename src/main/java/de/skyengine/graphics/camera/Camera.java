package de.skyengine.graphics.camera;

import de.skyengine.game.entity.EntityPlayer;
import org.joml.FrustumIntersection;
import org.joml.Matrix4f;
import org.joml.Vector3d;

public class Camera {

    /* Camera position uses doubles - at large world coordinates floats jitter */
    private final Vector3d position = new Vector3d();
    private float yaw, pitch;

    private float fov = 70.0F;
    private float nearPlane = 0.05F;
    private float farPlane = 1500.0F;

    private boolean inverseDepth = false;

    private final Matrix4f projection = new Matrix4f();
    private final Matrix4f view = new Matrix4f();
    private final Matrix4f projectionView = new Matrix4f();
    private final FrustumIntersection frustum = new FrustumIntersection();

    /**
     * Call once per frame before rendering. Interpolates between the player's last and current tick position.
     */
    public void follow(EntityPlayer player, float partialTick) {
        this.position.set(
                player.lastX + (player.x - player.lastX) * partialTick,
                player.lastY + (player.y - player.lastY) * partialTick + player.getEyeHeight(),
                player.lastZ + (player.z - player.lastZ) * partialTick
        );
        this.yaw = player.yaw;
        this.pitch = player.pitch;
    }

    /**
     * Recompute matrices. Call after follow() and after resize().
     */
    public void update(double aspectRatio) {
        if (this.inverseDepth) {
            /* Reversed-Z: far→0, near→1, Depth-Range [0,1] */
            this.projection.setPerspective(
                    (float) Math.toRadians(this.fov),
                    (float) aspectRatio,
                    this.farPlane, this.nearPlane,   // bewusst getauscht!
                    true                              // zZeroToOne
            );
        } else {
            this.projection.setPerspective(
                    (float) Math.toRadians(this.fov),
                    (float) aspectRatio,
                    this.nearPlane,
                    this.farPlane
            );
        }

        /* View matrix WITHOUT translation - chunks are rendered relative to the camera
           (camera-relative rendering avoids float precision issues far from origin) */
        this.view.rotationX((float) Math.toRadians(this.pitch))
                .rotateY((float) Math.toRadians(this.yaw));

        this.projection.mul(this.view, this.projectionView);
        this.frustum.set(this.projectionView, false);
    }

    public Vector3d getPosition() {
        return position;
    }

    public Matrix4f getProjectionViewMatrix() {
        return projectionView;
    }

    public FrustumIntersection getFrustum() {
        return frustum;
    }

    public float getYaw() {
        return yaw;
    }

    public float getPitch() {
        return pitch;
    }

    public void setFov(float fov) {
        this.fov = fov;
    }

    public void setInverseDepth(boolean value) {
        this.inverseDepth = value;
    }
}