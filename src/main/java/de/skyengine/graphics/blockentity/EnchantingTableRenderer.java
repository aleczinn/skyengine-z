package de.skyengine.graphics.blockentity;

import de.skyengine.core.file.FileHandle;
import de.skyengine.core.file.FileType;
import de.skyengine.game.world.block.entity.BlockEntity;
import de.skyengine.game.world.block.entity.EnchantingTableBlockEntity;
import de.skyengine.graphics.camera.Camera;
import de.skyengine.graphics.shader.Shader;
import de.skyengine.graphics.shader.ShaderProgram;
import de.skyengine.graphics.shader.ShaderType;
import de.skyengine.graphics.texture.Texture;
import org.joml.Matrix4f;
import org.joml.Vector3d;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

/**
 * Zeichnet das schwebende Buch des Zaubertischs - 1:1 nach Minecrafts {@code BookModel} +
 * {@code EnchantmentTableRenderer}: Buch dreht sich, wippt, ist geschlossen und öffnet sich mit
 * Seiten-Blättern, wenn ein Spieler nah ist (Zustand in {@link EnchantingTableBlockEntity}).
 *
 * <p>Geometrie in Modell-Pixeln (Tex {@value #TEX_W}×{@value #TEX_H}); per {@code scale(1/16)} in
 * Blockeinheiten gebracht. Kamerarelativ wie {@link ChestRenderer}. Buch flach/voll hell (kein
 * Richtungs-Shading -> kein Helligkeits-Flackern beim Drehen). Face-Culling beim Zeichnen aus
 * (dünne, beidseitig sichtbare Seiten), danach wiederhergestellt.
 */
public final class EnchantingTableRenderer implements BlockEntityRenderer {

    private static final int TEX_W = 64;
    private static final int TEX_H = 32;
    private static final int FLOATS_PER_VERTEX = 5;   // pos3 + uv2
    private static final float TILT = (float) Math.toRadians(80);

    private ShaderProgram shader;
    private Texture texture;

    private Mesh leftLid, rightLid, seam, leftPages, rightPages, flipPage1, flipPage2;

    private final Matrix4f base = new Matrix4f();
    private final Matrix4f part = new Matrix4f();

    @Override
    public void init() {
        this.shader = new ShaderProgram(
                new Shader(VERTEX, ShaderType.VERTEX),
                new Shader(FRAGMENT, ShaderType.FRAGMENT));
        this.texture = new Texture(
                new FileHandle("game/textures/entity/enchanting_table_book.png", FileType.RESOURCE), false);

        /* MC BookModel 1:1: texOffs(tu,tv) + addBox(ox,oy,oz, w,h,d). */
        this.leftLid = new Mesh(buildBox(-6, -5, -0.005f, 6, 10, 0.005f, 0, 0));    // Deckel links
        this.rightLid = new Mesh(buildBox(0, -5, -0.005f, 6, 10, 0.005f, 16, 0));   // Deckel rechts
        this.seam = new Mesh(buildBox(-1, -5, 0, 2, 10, 0.005f, 12, 0));            // Buchrücken (quer)
        this.leftPages = new Mesh(buildBox(0, -4, -0.99f, 5, 8, 1f, 0, 10));
        this.rightPages = new Mesh(buildBox(0, -4, -0.01f, 5, 8, 1f, 12, 10));
        this.flipPage1 = new Mesh(buildBox(0, -4, 0, 5, 8, 0.005f, 24, 10));
        this.flipPage2 = new Mesh(buildBox(0, -4, 0, 5, 8, 0.005f, 24, 10));
    }

    @Override
    public void render(BlockEntity be, Camera camera, float partialTick) {
        EnchantingTableBlockEntity table = (EnchantingTableBlockEntity) be;
        Vector3d cam = camera.getPosition();
        float ox = (float) (table.getPos().x() - cam.x);
        float oy = (float) (table.getPos().y() - cam.y);
        float oz = (float) (table.getPos().z() - cam.z);

        float time = table.getTime(partialTick);
        float open = table.getOpen(partialTick);
        float rot = table.getRot(partialTick);
        float flip = table.getFlip(partialTick);
        float hover = (float) Math.sin(time * 0.1f) * 0.01f + 0.1f;

        /* Buch-Öffnungswinkel + Seiten-Flip-Winkel (MC BookModel.setupAnim). */
        float f = ((float) Math.sin(time * 0.02f) * 0.1f + 1.25f) * open;
        float sinF = (float) Math.sin(f);
        float p1 = clamp(frac(flip + 0.25f) * 1.6f - 0.3f, 0f, 1f);
        float p2 = clamp(frac(flip + 0.75f) * 1.6f - 0.3f, 0f, 1f);

        /* Basis: Blockmitte + Höhe 0.75 + Wippe, Drehung, 80°-Neigung, dann px -> Block. */
        this.base.translation(ox + 0.5f, oy + 0.75f + hover, oz + 0.5f)
                .rotateY(-rot)
                .rotateZ(TILT)
                .scale(1f / 16f);

        boolean cull = GL11.glIsEnabled(GL11.GL_CULL_FACE);
        GL11.glDisable(GL11.GL_CULL_FACE);

        this.shader.bind();
        this.shader.setUniformMatrix4f("u_ProjectionView", camera.getProjectionViewMatrix());
        this.shader.setUniformi("u_Texture", 0);
        this.texture.bind(0);

        drawPart(this.leftLid, 0, 0, -1, (float) Math.PI + f);
        drawPart(this.rightLid, 0, 0, 1, -f);
        drawPart(this.seam, 0, 0, 0, (float) (Math.PI / 2));   // fest 90°, nicht animiert (wie MC)
        drawPart(this.leftPages, sinF, 0, 0, f);
        drawPart(this.rightPages, sinF, 0, 0, -f);
        drawPart(this.flipPage1, sinF, 0, 0, f - f * 2f * p1);
        drawPart(this.flipPage2, sinF, 0, 0, f - f * 2f * p2);

        this.shader.unbind();
        if (cull) GL11.glEnable(GL11.GL_CULL_FACE);
    }

    /** Zeichnet ein Buch-Teil: Pivot-Versatz (px) + Drehung um die lokale Y-Achse. */
    private void drawPart(Mesh mesh, float pivotX, float pivotY, float pivotZ, float yRot) {
        this.part.set(this.base).translate(pivotX, pivotY, pivotZ).rotateY(yRot);
        this.shader.setUniformMatrix4f("u_Model", this.part);
        mesh.render();
    }

    @Override
    public void dispose() {
        if (this.leftLid != null) this.leftLid.dispose();
        if (this.rightLid != null) this.rightLid.dispose();
        if (this.seam != null) this.seam.dispose();
        if (this.leftPages != null) this.leftPages.dispose();
        if (this.rightPages != null) this.rightPages.dispose();
        if (this.flipPage1 != null) this.flipPage1.dispose();
        if (this.flipPage2 != null) this.flipPage2.dispose();
        if (this.texture != null) this.texture.dispose();
        if (this.shader != null) this.shader.dispose();
    }

    private static float frac(float v) {
        return v - (float) Math.floor(v);
    }

    private static float clamp(float v, float min, float max) {
        return v < min ? min : Math.min(v, max);
    }

    /* --- Box-UV-Mesh-Bau: exakt Vanillas ModelPart.Cube (das Buch ist ein verbatim MC-Modell,
       daher KEINE Chest-Konvention - stbi lädt ohne Flip, v = tv/H sampelt top-down wie MC). --- */

    /** Box wie MC addBox(ox,oy,oz, w,h,d) in px mit Box-UV ab texOffs(tu,tv) -> pos3+uv2, 36 Vertices. */
    private static float[] buildBox(float ox, float oy, float oz, float w, float h, float d, int tu, int tv) {
        float x1 = ox, y1 = oy, z1 = oz;
        float x2 = ox + w, y2 = oy + h, z2 = oz + d;

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
        float U1 = tu1 / TEX_W, V1 = tv1 / TEX_H;
        float U2 = tu2 / TEX_W, V2 = tv2 / TEX_H;
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

    /* --- kleine VAO/VBO-Hülle --- */
    private static final class Mesh {
        private final int vao, vbo, count;

        Mesh(float[] data) {
            this.count = data.length / FLOATS_PER_VERTEX;
            this.vao = GL30.glGenVertexArrays();
            this.vbo = GL15.glGenBuffers();
            GL30.glBindVertexArray(this.vao);
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, this.vbo);
            GL15.glBufferData(GL15.GL_ARRAY_BUFFER, data, GL15.GL_STATIC_DRAW);
            de.skyengine.graphics.GlDebug.labelBuffer(this.vbo, "EnchantingTableRenderer Mesh-VBO");
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

    private static final String VERTEX = """
        #version 460 core
        layout(location = 0) in vec3 a_position;
        layout(location = 1) in vec2 a_uv;
        uniform mat4 u_ProjectionView;
        uniform mat4 u_Model;
        out vec2 v_uv;
        void main() {
            v_uv = a_uv;
            gl_Position = u_ProjectionView * u_Model * vec4(a_position, 1.0);
        }
        """;

    private static final String FRAGMENT = """
        #version 460 core
        in vec2 v_uv;
        uniform sampler2D u_Texture;
        out vec4 fragColor;
        void main() {
            vec4 c = texture(u_Texture, v_uv);
            if (c.a < 0.5) discard;
            fragColor = c;
        }
        """;
}
