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

    private Mesh leftLid, rightLid, leftPages, rightPages, flipPage1, flipPage2;

    private final Matrix4f base = new Matrix4f();
    private final Matrix4f part = new Matrix4f();

    @Override
    public void init() {
        this.shader = new ShaderProgram(
                new Shader(VERTEX, ShaderType.VERTEX),
                new Shader(FRAGMENT, ShaderType.FRAGMENT));
        this.texture = new Texture(
                new FileHandle("game/textures/entity/enchanting_table_book.png", FileType.RESOURCE), false);

        /* MC BookModel: texOffs + addBox(ox,oy,oz, w,h,d). +Y oben (UV-Flip im quad()). */
        this.leftLid = new Mesh(buildBox(-6, -5, 0, 0, 5, 0.005f, 0, 0));    // Deckel links
        this.rightLid = new Mesh(buildBox(0, -5, 0, 6, 5, 0.005f, 16, 0));   // Deckel rechts
        this.leftPages = new Mesh(buildBox(0, -4, -0.99f, 5, 4, 0.01f, 0, 10));
        this.rightPages = new Mesh(buildBox(0, -4, -0.99f, 5, 4, 0.01f, 12, 10));
        this.flipPage1 = new Mesh(buildBox(0, -4, 0, 5, 4, 0.005f, 24, 10));
        this.flipPage2 = new Mesh(buildBox(0, -4, 0, 5, 4, 0.005f, 24, 10));
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

    /* --- Box-UV-Mesh-Bau (Minecraft-Layout, Float-Maße) wie ChestRenderer, ohne Normalen --- */

    /** Box von (x0,y0,z0) bis (x1,y1,z1) in px mit Box-UV ab Offset (tu,tv) -> pos3+uv2, 36 Vertices. */
    private static float[] buildBox(float x0, float y0, float z0, float x1, float y1, float z1, int tu, int tv) {
        float w = x1 - x0, h = y1 - y0, d = z1 - z0;
        float[] buf = new float[36 * FLOATS_PER_VERTEX];
        int[] i = {0};

        quad(buf, i, x0, y1, z1, x1, y1, z1, x1, y1, z0, x0, y1, z0, tu + d + w,     tv,     w, d); // up (+y)
        quad(buf, i, x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1, tu + d,         tv,     w, d); // down (-y)
        quad(buf, i, x1, y0, z0, x0, y0, z0, x0, y1, z0, x1, y1, z0, tu + d,         tv + d, w, h); // north (-z)
        quad(buf, i, x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1, tu + d + w + d, tv + d, w, h); // south (+z)
        quad(buf, i, x0, y0, z0, x0, y0, z1, x0, y1, z1, x0, y1, z0, tu,             tv + d, d, h); // west (-x)
        quad(buf, i, x1, y0, z1, x1, y0, z0, x1, y1, z0, x1, y1, z1, tu + d + w,     tv + d, d, h); // east (+x)
        return buf;
    }

    /* Ein Face: 4 Ecken a..d + UV-Rechteck. Vertikal in-place gespiegelt wie ChestRenderer (Engine
       sampelt bottom-up, Vanilla-Box-UV ist top-down authored). */
    private static void quad(float[] buf, int[] i,
                             float ax, float ay, float az, float bx, float by, float bz,
                             float cx, float cy, float cz, float dx, float dy, float dz,
                             float tu, float tv, float tw, float th) {
        float u0 = tu / TEX_W, v0 = tv / TEX_H;
        float u1 = (tu + tw) / TEX_W, v1 = (tv + th) / TEX_H;
        vert(buf, i, ax, ay, az, u0, v0);
        vert(buf, i, bx, by, bz, u1, v0);
        vert(buf, i, cx, cy, cz, u1, v1);
        vert(buf, i, ax, ay, az, u0, v0);
        vert(buf, i, cx, cy, cz, u1, v1);
        vert(buf, i, dx, dy, dz, u0, v1);
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
