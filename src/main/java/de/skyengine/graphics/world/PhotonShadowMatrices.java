package de.skyengine.graphics.world;

import org.joml.Matrix4f;
import org.joml.Vector3d;

/**
 * Iris' shadow-matrix setup for Photon's default shadow configuration.
 *
 * <p>These values and the operation order deliberately mirror
 * {@code net.irisshaders.iris.shadows.ShadowMatrices}: Photon assumes a symmetric 128 block
 * orthographic volume, Iris' asymmetric light-space depth interval and a camera translation
 * snapped to {@code shadowIntervalSize}. Replacing this with a look-at matrix or texel-sized
 * snapping changes the sampling phase whenever the camera moves and makes PCSS shadows crawl.
 */
public final class PhotonShadowMatrices {

    public static final float SHADOW_DISTANCE = 128F;
    public static final float SHADOW_NEAR = -100.05F;
    public static final float SHADOW_FAR = 156F;
    public static final float SHADOW_INTERVAL_SIZE = 2F;
    public static final float SUN_PATH_ROTATION = -35F;
    public static final float SHADOW_DEPTH_RANGE = (SHADOW_FAR - SHADOW_NEAR) * 0.5F;

    private PhotonShadowMatrices() {
    }

    public static Matrix4f projection(Matrix4f destination) {
        return destination.identity().setOrthoSymmetric(
                SHADOW_DISTANCE * 2F,
                SHADOW_DISTANCE * 2F,
                SHADOW_NEAR,
                SHADOW_FAR);
    }

    public static Matrix4f modelView(float shadowAngle, Vector3d camera,
                                     Matrix4f destination) {
        float adjustedAngle = shadowAngle < 0.25F
                ? shadowAngle + 0.75F
                : shadowAngle - 0.25F;

        destination.identity()
                .rotateX((float) (Math.PI * 0.5))
                .rotateZ((float) Math.toRadians(adjustedAngle * -360F))
                .rotateX((float) Math.toRadians(SUN_PATH_ROTATION));

        float interval = SHADOW_INTERVAL_SIZE;
        float halfInterval = interval * 0.5F;
        /* Iris castet zuerst auf float und benutzt JVM-frem (%), auch bei negativen
           Koordinaten. floorMod wäre dort sichtbar um ein ganzes Intervall versetzt. */
        float offsetX = (float) camera.x % interval - halfInterval;
        float offsetY = (float) camera.y % interval - halfInterval;
        float offsetZ = (float) camera.z % interval - halfInterval;
        return destination.translate(offsetX, offsetY, offsetZ);
    }

    public static Matrix4f projectionView(float shadowAngle, Vector3d camera,
                                          Matrix4f projection,
                                          Matrix4f modelView,
                                          Matrix4f destination) {
        projection(projection);
        modelView(shadowAngle, camera, modelView);
        return projection.mul(modelView, destination);
    }
}
