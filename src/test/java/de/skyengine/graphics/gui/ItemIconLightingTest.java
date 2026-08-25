package de.skyengine.graphics.gui;

import org.joml.Matrix4f;
import org.joml.Matrix3f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ItemIconLightingTest {

    @Test
    void minecraftItemLightingAlwaysKeepsFortyPercentAmbientLight() {
        Matrix4f identity = new Matrix4f();
        Vector3f[] normals = {
                new Vector3f(1, 0, 0), new Vector3f(-1, 0, 0),
                new Vector3f(0, 1, 0), new Vector3f(0, -1, 0),
                new Vector3f(0, 0, 1), new Vector3f(0, 0, -1)
        };
        float darkest = 1F;
        for (Vector3f normal : normals) {
            float brightness = ItemIconLighting.brightness3D(identity, normal);
            assertTrue(brightness >= ItemIconLighting.AMBIENT_LIGHT);
            assertTrue(brightness <= 1F);
            darkest = Math.min(darkest, brightness);
        }
        assertEquals(ItemIconLighting.AMBIENT_LIGHT, darkest, 0.0001F);
    }

    @Test
    void guiRotationParticipatesInNormalLighting() {
        Matrix4f cube = new Matrix4f().rotateXYZ(
                (float) Math.toRadians(30), (float) Math.toRadians(225), 0F).scale(0.625F);
        Matrix4f gate = new Matrix4f().rotateXYZ(
                (float) Math.toRadians(30), (float) Math.toRadians(45), 0F).scale(0.8F);

        float cubeSide = ItemIconLighting.brightness3D(cube, new Vector3f(1, 0, 0));
        float gateSide = ItemIconLighting.brightness3D(gate, new Vector3f(1, 0, 0));
        assertNotEquals(cubeSide, gateSide, 0.0001F);
    }

    @Test
    void engineCoordinatesMatchMinecraftsMirroredGuiPose() {
        Vector3f base0 = new Vector3f(0.2F, 1F, -0.7F).normalize();
        Vector3f base1 = new Vector3f(-0.2F, 1F, 0.7F).normalize();
        Matrix4f minecraftLighting = new Matrix4f()
                .scaling(1F, -1F, 1F)
                .rotateYXZ(1.0821041F, 3.2375858F, 0F)
                .rotateYXZ(-0.3926991F, 2.3561945F, 0F);
        Vector3f minecraftLight0 = minecraftLighting.transformDirection(base0, new Vector3f());
        Vector3f minecraftLight1 = minecraftLighting.transformDirection(base1, new Vector3f());

        Matrix4f engineGui = new Matrix4f().rotateXYZ(
                (float) Math.toRadians(30), (float) Math.toRadians(225), 0F).scale(0.625F);
        Matrix4f minecraftGui = new Matrix4f().scaling(1F, -1F, 1F).mul(engineGui);
        Vector3f[] normals = {
                new Vector3f(0, 1, 0), new Vector3f(1, 0, 0), new Vector3f(-1, 0, 0),
                new Vector3f(0, 0, 1), new Vector3f(0, 0, -1)
        };

        for (Vector3f normal : normals) {
            Vector3f minecraftNormal = minecraftGui.normal(new Matrix3f())
                    .transform(new Vector3f(normal)).normalize();
            float diffuse = Math.max(0F, minecraftLight0.dot(minecraftNormal))
                    + Math.max(0F, minecraftLight1.dot(minecraftNormal));
            float expected = Math.min(1F, diffuse * ItemIconLighting.LIGHT_POWER
                    + ItemIconLighting.AMBIENT_LIGHT);
            assertEquals(expected, ItemIconLighting.brightness3D(engineGui, normal), 0.0001F);
        }
    }

    @Test
    void standardCubeKeepsItsTopBrightAndShadesItsSides() {
        Matrix4f cube = new Matrix4f().rotateXYZ(
                (float) Math.toRadians(30), (float) Math.toRadians(225), 0F).scale(0.625F);
        float top = ItemIconLighting.brightness3D(cube, new Vector3f(0, 1, 0));
        float sideX = ItemIconLighting.brightness3D(cube, new Vector3f(1, 0, 0));
        /* Bei der GUI-Rotation 225 Grad zeigen +X und -Z zur Kamera. */
        float sideZ = ItemIconLighting.brightness3D(cube, new Vector3f(0, 0, -1));

        assertTrue(top > sideX);
        assertTrue(top > sideZ);
        assertNotEquals(sideX, sideZ, 0.0001F);
    }

    @Test
    void flatItemsUseTheMinecraftFlatLightSet() {
        float brightness = ItemIconLighting.brightnessFlat(new Vector3f(0, 0, 1));
        assertTrue(brightness >= ItemIconLighting.AMBIENT_LIGHT);
        assertTrue(brightness <= 1F);
    }
}
