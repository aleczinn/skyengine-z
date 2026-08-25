package de.skyengine.graphics.gui;

import de.skyengine.graphics.shader.ShaderProgram;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;
import org.joml.Vector3fc;

/**
 * Minecraft-26.2-Beleuchtung fuer Inventar-Items. Vanilla mischt zwei gerichtete Lichter mit
 * 40 % Umgebungs- und 60 % Diffuslicht; die beiden Transform-Saetze stammen wortgetreu aus
 * {@code com.mojang.blaze3d.platform.Lighting} (ITEMS_FLAT/ITEMS_3D).
 */
public final class ItemIconLighting {

    public static final float LIGHT_POWER = 0.6F;
    public static final float AMBIENT_LIGHT = 0.4F;

    private static final Vector3f FLAT_LIGHT_0;
    private static final Vector3f FLAT_LIGHT_1;
    private static final Vector3f LIGHT_3D_0;
    private static final Vector3f LIGHT_3D_1;

    static {
        Vector3f base0 = new Vector3f(0.2F, 1F, -0.7F).normalize();
        Vector3f base1 = new Vector3f(-0.2F, 1F, 0.7F).normalize();

        Matrix4f flat = new Matrix4f()
                .rotationY(-0.3926991F)
                .rotateX(2.3561945F);
        FLAT_LIGHT_0 = flat.transformDirection(base0, new Vector3f());
        FLAT_LIGHT_1 = flat.transformDirection(base1, new Vector3f());

        Matrix4f threeDimensional = new Matrix4f()
                .scaling(1F, -1F, 1F)
                .rotateYXZ(1.0821041F, 3.2375858F, 0F)
                .rotateYXZ(-0.3926991F, 2.3561945F, 0F);
        Vector3f minecraftLight0 = threeDimensional.transformDirection(base0, new Vector3f());
        Vector3f minecraftLight1 = threeDimensional.transformDirection(base1, new Vector3f());

        /* Minecraft rendert GUI-Items nach scale(size, -size, size): Normalen UND die
           ITEMS_3D-Lichter tragen deshalb dieselbe Y-Spiegelung, die sich im Skalarprodukt
           aufhebt. Unsere GUI-Projektion ist bereits Y-up und besitzt diese Pose-Spiegelung
           nicht. Die Lichtvektoren muessen daher einmal in den Engine-Raum zurueckgespiegelt
           werden; andernfalls wird nur das Licht gespiegelt und ausgerechnet die Oberseite
           dunkel. S^-1 = S fuer diese Spiegelmatrix. */
        LIGHT_3D_0 = minecraftLight0.mul(1F, -1F, 1F);
        LIGHT_3D_1 = minecraftLight1.mul(1F, -1F, 1F);
    }

    private ItemIconLighting() {}

    public static void apply3D(ShaderProgram shader) {
        apply(shader, LIGHT_3D_0, LIGHT_3D_1);
    }

    public static void applyFlat(ShaderProgram shader) {
        apply(shader, FLAT_LIGHT_0, FLAT_LIGHT_1);
    }

    private static void apply(ShaderProgram shader, Vector3fc light0, Vector3fc light1) {
        shader.setUniformVector3f("u_ItemLight0", light0.x(), light0.y(), light0.z());
        shader.setUniformVector3f("u_ItemLight1", light1.x(), light1.y(), light1.z());
    }

    static float brightness3D(Matrix4fc model, Vector3fc normal) {
        Vector3f transformed = model.normal(new Matrix3f())
                .transform(new Vector3f(normal))
                .normalize();
        return brightness(transformed, LIGHT_3D_0, LIGHT_3D_1);
    }

    static float brightnessFlat(Vector3fc normal) {
        return brightness(normal, FLAT_LIGHT_0, FLAT_LIGHT_1);
    }

    private static float brightness(Vector3fc normal, Vector3fc light0, Vector3fc light1) {
        float diffuse = Math.max(0F, light0.dot(normal)) + Math.max(0F, light1.dot(normal));
        return Math.min(1F, diffuse * LIGHT_POWER + AMBIENT_LIGHT);
    }
}
