package de.skyengine.graphics.world;

import org.joml.Matrix4f;
import org.joml.Vector3d;
import org.joml.Vector4f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class PhotonShadowMatricesTest {

    private static final float EPSILON = 1.0E-5F;

    @Test
    void projectionMatchesPhotonIrisDefaults() {
        Matrix4f projection = PhotonShadowMatrices.projection(new Matrix4f());

        assertEquals(1F / 128F, projection.m00(), EPSILON);
        assertEquals(1F / 128F, projection.m11(), EPSILON);
        assertEquals(PhotonShadowMatrices.SHADOW_DEPTH_RANGE,
                (PhotonShadowMatrices.SHADOW_FAR - PhotonShadowMatrices.SHADOW_NEAR) * 0.5F,
                EPSILON);
    }

    @Test
    void cameraRelativeShadowCoordinatesStayWorldLockedWithinInterval() {
        float angle = 0.41F;
        Vector3d cameraA = new Vector3d(69.15, 76.2, 36.4);
        Vector3d cameraB = new Vector3d(69.72, 76.73, 37.01);
        Vector3d worldPoint = new Vector3d(81.25, 70.0, 29.75);

        Vector4f clipA = transformCameraRelative(angle, cameraA, worldPoint);
        Vector4f clipB = transformCameraRelative(angle, cameraB, worldPoint);

        assertEquals(clipA.x, clipB.x, EPSILON);
        assertEquals(clipA.y, clipB.y, EPSILON);
        assertEquals(clipA.z, clipB.z, EPSILON);
    }

    private static Vector4f transformCameraRelative(float angle, Vector3d camera,
                                                     Vector3d worldPoint) {
        Matrix4f matrix = PhotonShadowMatrices.projectionView(
                angle, camera, new Matrix4f(), new Matrix4f(), new Matrix4f());
        return matrix.transform(new Vector4f(
                (float) (worldPoint.x - camera.x),
                (float) (worldPoint.y - camera.y),
                (float) (worldPoint.z - camera.z),
                1F));
    }
}
