package de.skyengine.graphics.player;

import de.skyengine.graphics.GlDebug;
import de.skyengine.graphics.shader.ShaderProgram;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

/**
 * Minecraft-Humanoid aus Boxen für Classic-Skins (64×64, 4px-Arme) — Koordinaten, Pivots und
 * Pose-Winkel VERBATIM aus Vanillas HumanoidModel (y-down, Einheit px). Die Umrechnung in die
 * y-up-Welt macht ausschließlich {@link #applyModelSpace}: Vanilla-Spieler-Scale 0.9375,
 * px→Block, 180°-X-Rotation (echte Rotation, kein Mirror — kein Chirality-Flip) und
 * Fußpunkt auf den Ursprung. So gelten alle Vanilla-Animationsformeln 1:1 und das
 * Box-UV-Mapping (buildBox erwartet y-down!) stimmt — NICHT wieder in y-up spiegeln,
 * das war der Textur-Flip-Bug der ersten Runde.
 *
 * <p>Jedes Teil hat einen Overlay-Layer (Hut/Jacke/Ärmel/Hose): Geometrie dilatiert
 * ({@code grow}, MC-Dilation), UV-Stationen aus den NOMINALEN Maßen — verhindert Z-Fighting;
 * transparente Overlay-Pixel verwirft der Shader per discard.
 */
public final class PlayerModel {

    private static final int TEX = 64;                // Skin ist 64×64
    private static final int FLOATS_PER_VERTEX = 5;   // pos3 + uv2

    private Mesh head, hat, body, jacket;
    private Mesh rightArm, rightSleeve, leftArm, leftSleeve;
    private Mesh rightLeg, rightPants, leftLeg, leftPants;

    /* Slim-Variante (Alex, 3px-Arme): schmalere Arm-Boxen, Arm-Pivot 0.5px tiefer. */
    private boolean slim;

    private final Matrix4f part = new Matrix4f();

    /** Baut alle Part-Meshes (GL — nur auf dem Render-Thread). */
    public void init(boolean slim) {
        this.slim = slim;
        /* Vanilla HumanoidModel/PlayerModel: addBox relativ zum Part-Pivot. */
        this.head = new Mesh(buildBox(-4, -8, -4, 8, 8, 8, 0, 0, 0));
        this.hat = new Mesh(buildBox(-4, -8, -4, 8, 8, 8, 32, 0, 0.5f));
        this.body = new Mesh(buildBox(-4, 0, -2, 8, 12, 4, 16, 16, 0));
        this.jacket = new Mesh(buildBox(-4, 0, -2, 8, 12, 4, 16, 32, 0.25f));
        if (slim) {
            this.rightArm = new Mesh(buildBox(-2, -2, -2, 3, 12, 4, 40, 16, 0));
            this.rightSleeve = new Mesh(buildBox(-2, -2, -2, 3, 12, 4, 40, 32, 0.25f));
            this.leftArm = new Mesh(buildBox(-1, -2, -2, 3, 12, 4, 32, 48, 0));
            this.leftSleeve = new Mesh(buildBox(-1, -2, -2, 3, 12, 4, 48, 48, 0.25f));
        } else {
            this.rightArm = new Mesh(buildBox(-3, -2, -2, 4, 12, 4, 40, 16, 0));
            this.rightSleeve = new Mesh(buildBox(-3, -2, -2, 4, 12, 4, 40, 32, 0.25f));
            this.leftArm = new Mesh(buildBox(-1, -2, -2, 4, 12, 4, 32, 48, 0));
            this.leftSleeve = new Mesh(buildBox(-1, -2, -2, 4, 12, 4, 48, 48, 0.25f));
        }
        this.rightLeg = new Mesh(buildBox(-2, 0, -2, 4, 12, 4, 0, 16, 0));
        this.rightPants = new Mesh(buildBox(-2, 0, -2, 4, 12, 4, 0, 32, 0.25f));
        this.leftLeg = new Mesh(buildBox(-2, 0, -2, 4, 12, 4, 16, 48, 0));
        this.leftPants = new Mesh(buildBox(-2, 0, -2, 4, 12, 4, 0, 48, 0.25f));
    }

    public boolean isSlim() {
        return this.slim;
    }

    /** Arm-Pivot-Höhe (Vanilla: slim 2.5, classic 2.0) — nach jedem Pose-Reset setzen. */
    public float getArmPivotY() {
        return this.slim ? 2.5f : 2f;
    }

    /**
     * Hängt den Modell-Raum an eine Welt-/GUI-Matrix an: danach liegen Vanilla-y-down-px
     * mit dem Fußpunkt auf dem Ursprung, y-up, Front = +Z. Für unsere Yaw-Konvention
     * (yaw 0 blickt −Z) davor {@code rotateY(PI − toRadians(bodyYaw))} anwenden.
     */
    public static Matrix4f applyModelSpace(Matrix4f m) {
        return m.scale(0.9375f / 16f)              // Vanilla-Spieler-Scale + px -> Block
                .rotateX((float) Math.PI)          // y-down -> y-up (echte Rotation)
                .translate(0, -24f, 0);            // Füße (y-down 24) auf den Ursprung
    }

    /**
     * Zeichnet das komplette Modell. {@code base} = Matrix bis einschließlich
     * {@link #applyModelSpace}; der Shader muss gebunden sein und {@code u_Model} kennen.
     */
    public void render(ShaderProgram shader, Matrix4f base, Pose pose) {
        this.draw(shader, this.part.set(base)
                .translate(0, pose.headY, 0)
                .rotateY(pose.headYRot).rotateX(pose.headXRot), this.head, this.hat);
        this.draw(shader, this.part.set(base)
                .translate(0, pose.bodyY, 0)
                .rotateY(pose.bodyYRot).rotateX(pose.bodyXRot), this.body, this.jacket);
        this.draw(shader, this.rightArmMatrix(base, pose, this.part), this.rightArm, this.rightSleeve);
        this.draw(shader, this.part.set(base)
                .translate(pose.leftArmX, pose.armY, pose.leftArmZ)
                .rotateZ(pose.leftArmZRot).rotateY(pose.leftArmYRot).rotateX(pose.leftArmXRot),
                this.leftArm, this.leftSleeve);
        this.draw(shader, this.part.set(base)
                .translate(-1.9f, pose.legY, pose.legZ)
                .rotateZ(pose.rightLegZRot).rotateY(pose.rightLegYRot).rotateX(pose.rightLegXRot),
                this.rightLeg, this.rightPants);
        this.draw(shader, this.part.set(base)
                .translate(1.9f, pose.legY, pose.legZ)
                .rotateZ(pose.leftLegZRot).rotateY(pose.leftLegYRot).rotateX(pose.leftLegXRot),
                this.leftLeg, this.leftPants);
    }

    /** Rechte-Arm-Matrix (auch Anker fürs Held-Item), Vanilla-Rotationsfolge Z→Y→X. */
    public Matrix4f rightArmMatrix(Matrix4f base, Pose pose, Matrix4f dest) {
        return dest.set(base)
                .translate(pose.rightArmX, pose.armY, pose.rightArmZ)
                .rotateZ(pose.rightArmZRot).rotateY(pose.rightArmYRot).rotateX(pose.rightArmXRot);
    }

    /** Nur der rechte Arm mit Ärmel (First-Person-Hand); {@code matrix} = fertige Part-Matrix. */
    public void renderRightArm(ShaderProgram shader, Matrix4f matrix) {
        this.draw(shader, matrix, this.rightArm, this.rightSleeve);
    }

    private void draw(ShaderProgram shader, Matrix4f matrix, Mesh mesh, Mesh overlay) {
        shader.setUniformMatrix4f("u_Model", matrix);
        mesh.render();
        overlay.render();
    }

    public void dispose() {
        for (Mesh mesh : new Mesh[]{this.head, this.hat, this.body, this.jacket,
                this.rightArm, this.rightSleeve, this.leftArm, this.leftSleeve,
                this.rightLeg, this.rightPants, this.leftLeg, this.leftPants}) {
            if (mesh != null) mesh.dispose();
        }
    }

    /**
     * Pose eines Frames — Vanilla-Semantik: Winkel in Radiant (y-down!), Pivots in px.
     * Alle HumanoidModel-Formeln (Walk/Attack/Crouch) gelten hier VERBATIM.
     */
    public static final class Pose {
        public float headXRot, headYRot;
        public float bodyXRot, bodyYRot;
        public float rightArmXRot, rightArmYRot, rightArmZRot;
        public float leftArmXRot, leftArmYRot, leftArmZRot;
        public float rightLegXRot, rightLegYRot, rightLegZRot;
        public float leftLegXRot, leftLegYRot, leftLegZRot;
        /* Pivots (Vanilla-Defaults; Crouch/Attack verschieben sie). */
        public float headY = 0, bodyY = 0;
        public float rightArmX = -5, rightArmZ = 0, leftArmX = 5, leftArmZ = 0, armY = 2;
        public float legY = 12, legZ = 0;

        public void reset() {
            this.headXRot = 0; this.headYRot = 0;
            this.bodyXRot = 0; this.bodyYRot = 0;
            this.rightArmXRot = 0; this.rightArmYRot = 0; this.rightArmZRot = 0;
            this.leftArmXRot = 0; this.leftArmYRot = 0; this.leftArmZRot = 0;
            this.rightLegXRot = 0; this.rightLegYRot = 0; this.rightLegZRot = 0;
            this.leftLegXRot = 0; this.leftLegYRot = 0; this.leftLegZRot = 0;
            this.headY = 0; this.bodyY = 0;
            this.rightArmX = -5; this.rightArmZ = 0; this.leftArmX = 5; this.leftArmZ = 0;
            this.armY = 2;
            this.legY = 12; this.legZ = 0;
        }
    }

    /* --- Box-UV-Mesh-Bau: exakt Vanillas ModelPart.Cube (Kopie aus EnchantingTableRenderer,
       erweitert um grow = MC-Dilation; UV-Stationen bleiben auf den nominalen Maßen).
       Erwartet y-DOWN-Koordinaten — nur dann stimmt die V-Zuordnung der Faces. --- */

    private static float[] buildBox(float ox, float oy, float oz, int w, int h, int d, int tu, int tv, float g) {
        float x1 = ox - g, y1 = oy - g, z1 = oz - g;
        float x2 = ox + w + g, y2 = oy + h + g, z2 = oz + d + g;

        /* UV-Stationen des Vanilla-Layouts: Reihe 1 [down][up], Reihe 2 [west][north][east][south]. */
        float u0 = tu, u1 = tu + d, u2 = tu + d + w, u3 = u2 + w, u4 = u2 + d, u5 = u4 + w;
        float va = tv, vb = tv + d, vc = vb + h;

        float[] buf = new float[36 * FLOATS_PER_VERTEX];
        int[] i = {0};
        face(buf, i, x2, y1, z2, x1, y1, z2, x1, y1, z1, x2, y1, z1, u1, va, u2, vb); // down (y1)
        face(buf, i, x2, y2, z1, x1, y2, z1, x1, y2, z2, x2, y2, z2, u2, vb, u3, va); // up (y2), V invertiert
        face(buf, i, x1, y1, z1, x1, y1, z2, x1, y2, z2, x1, y2, z1, u0, vb, u1, vc); // west (x1)
        face(buf, i, x2, y1, z1, x1, y1, z1, x1, y2, z1, x2, y2, z1, u1, vb, u2, vc); // north (z1)
        face(buf, i, x2, y1, z2, x2, y1, z1, x2, y2, z1, x2, y2, z2, u2, vb, u4, vc); // east (x2)
        face(buf, i, x1, y1, z2, x2, y1, z2, x2, y2, z2, x1, y2, z2, u4, vb, u5, vc); // south (z2)
        return buf;
    }

    /* Ein Face nach Vanillas Polygon-Remap: a->(U2,V1), b->(U1,V1), c->(U1,V2), d->(U2,V2).
       Triangulierung {a,b,c} + {a,c,d}; Winding unkritisch (Culling beim Zeichnen aus). */
    private static void face(float[] buf, int[] i,
                             float ax, float ay, float az, float bx, float by, float bz,
                             float cx, float cy, float cz, float dx, float dy, float dz,
                             float tu1, float tv1, float tu2, float tv2) {
        float U1 = tu1 / TEX, V1 = tv1 / TEX;
        float U2 = tu2 / TEX, V2 = tv2 / TEX;
        vert(buf, i, ax, ay, az, U2, V1);
        vert(buf, i, bx, by, bz, U1, V1);
        vert(buf, i, cx, cy, cz, U1, V2);
        vert(buf, i, ax, ay, az, U2, V1);
        vert(buf, i, cx, cy, cz, U1, V2);
        vert(buf, i, dx, dy, dz, U2, V2);
    }

    private static void vert(float[] buf, int[] i, float x, float y, float z, float u, float v) {
        buf[i[0]++] = x; buf[i[0]++] = y; buf[i[0]++] = z;
        buf[i[0]++] = u; buf[i[0]++] = v;
    }

    /* --- kleine VAO/VBO-Hülle (Kopie EnchantingTableRenderer) --- */
    private static final class Mesh {
        private final int vao, vbo, count;

        Mesh(float[] data) {
            this.count = data.length / FLOATS_PER_VERTEX;
            this.vao = GL30.glGenVertexArrays();
            this.vbo = GL15.glGenBuffers();
            GL30.glBindVertexArray(this.vao);
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, this.vbo);
            GL15.glBufferData(GL15.GL_ARRAY_BUFFER, data, GL15.GL_STATIC_DRAW);
            GlDebug.labelBuffer(this.vbo, "PlayerModel Mesh-VBO");
            int stride = FLOATS_PER_VERTEX * Float.BYTES;
            GL20.glVertexAttribPointer(0, 3, GL11.GL_FLOAT, false, stride, 0);
            GL20.glVertexAttribPointer(1, 2, GL11.GL_FLOAT, false, stride, 3 * Float.BYTES);
            GL20.glEnableVertexAttribArray(0);
            GL20.glEnableVertexAttribArray(1);
            GL30.glBindVertexArray(0);
        }

        void render() {
            GL30.glBindVertexArray(this.vao);
            GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, this.count);
        }

        void dispose() {
            GL30.glDeleteVertexArrays(this.vao);
            GL15.glDeleteBuffers(this.vbo);
        }
    }
}
