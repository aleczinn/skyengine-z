package de.skyengine.graphics.blockentity;

import de.skyengine.core.file.FileHandle;
import de.skyengine.core.file.FileType;
import de.skyengine.game.world.block.Direction;
import de.skyengine.game.world.block.entity.BlockEntity;
import de.skyengine.game.world.block.entity.ChestBlockEntity;
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
 * Zeichnet die Truhe als zweiteiliges Modell (Korpus + Deckel) mit der Entity-Textur
 * {@code entity/chest/normal.png}. Der Deckel kippt um die hintere Scharnierkante anhand des
 * interpolierten Öffnungsgrads ({@link ChestBlockEntity#getOpenness}).
 *
 * <p>Geometrie/UVs folgen dem Minecraft-Box-UV-Layout (Korpus UV-Offset 0,19; Deckel 0,0).
 * Face-Culling ist beim Zeichnen deaktiviert (robust gegen Winding), danach wiederhergestellt.
 */
public final class ChestRenderer implements BlockEntityRenderer {

    private static final int TEX = 64;               // Texturgröße in px (vanilla normal.png)
    private static final int FLOATS_PER_VERTEX = 5;  // pos3 + uv2
    private static final float MAX_ANGLE = (float) Math.toRadians(90);

    /* Scharnier: hintere Unterkante des Deckels (Blockeinheiten). MC-Maße: Truhe ist 14px hoch,
       Deckel sitzt von 9..14, Scharnier an der hinteren Kante bei y=9, z=1. */
    private static final float HINGE_Y = 9f / 16f;
    private static final float HINGE_Z = 1f / 16f;

    private ShaderProgram shader;
    private Texture texture;
    private Mesh base;
    private Mesh lid;
    private Mesh latch;   // Schloss/Henkel vorne (bewegt sich mit dem Deckel)

    private final Matrix4f model = new Matrix4f();
    private final Matrix4f iconModel = new Matrix4f();

    @Override
    public void init() {
        this.shader = new ShaderProgram(
                new Shader(VERTEX, ShaderType.VERTEX),
                new Shader(FRAGMENT, ShaderType.FRAGMENT));
        this.texture = new Texture(new FileHandle("game/textures/entity/chest/normal.png", FileType.RESOURCE), false);

        this.base = new Mesh(buildBox(1, 0, 1, 15, 10, 15, 0, 19));   // Korpus (0..10)
        this.lid = new Mesh(buildBox(1, 9, 1, 15, 14, 15, 0, 0));     // Deckel (9..14, MC-Höhe)
        this.latch = new Mesh(buildBox(7, 7, 15, 9, 11, 16, 0, 0));   // Schloss vorne (UV wie Vanilla-Knob)
    }

    @Override
    public void render(BlockEntity be, Camera camera, float partialTick) {
        ChestBlockEntity chest = (ChestBlockEntity) be;
        Vector3d cam = camera.getPosition();
        float ox = (float) (chest.getPos().x() - cam.x);
        float oy = (float) (chest.getPos().y() - cam.y);
        float oz = (float) (chest.getPos().z() - cam.z);
        float angle = chest.getOpenness(partialTick) * MAX_ANGLE;

        /* Facing-Drehung: kanonische Modellfront ist +Z (SOUTH). rotateY(atan2(dx,dz)) bildet +Z
           geometrisch korrekt auf die Zielrichtung ab — unabhängig von der Hand-Konvention. */
        Direction facing = chest.getFacing();
        float facingY = (float) Math.atan2(facing.offsetX(), facing.offsetZ());

        boolean cull = GL11.glIsEnabled(GL11.GL_CULL_FACE);
        GL11.glDisable(GL11.GL_CULL_FACE);

        this.shader.bind();
        this.shader.setUniformMatrix4f("u_ProjectionView", camera.getProjectionViewMatrix());
        this.shader.setUniformi("u_Texture", 0);
        this.texture.bind(0);

        /* Korpus: nur Facing-Drehung um die vertikale Blockmitte (0.5, *, 0.5). */
        this.model.translation(ox, oy, oz)
                .translate(0.5f, 0f, 0.5f).rotateY(facingY).translate(-0.5f, 0f, -0.5f);
        this.shader.setUniformMatrix4f("u_Model", this.model);
        this.base.render();

        /* Deckel + Schloss: Facing-Drehung, dann Aufklappen um die hintere Scharnierkante. */
        this.model.translation(ox, oy, oz)
                .translate(0.5f, 0f, 0.5f).rotateY(facingY).translate(-0.5f, 0f, -0.5f)
                .translate(0, HINGE_Y, HINGE_Z)
                .rotateX(-angle)
                .translate(0, -HINGE_Y, -HINGE_Z);
        this.shader.setUniformMatrix4f("u_Model", this.model);
        this.lid.render();
        this.latch.render();

        this.shader.unbind();
        if (cull) GL11.glEnable(GL11.GL_CULL_FACE);
    }

    @Override
    public boolean hasIcon() {
        return true;
    }

    /**
     * Zeichnet die GESCHLOSSENE Truhe als Inventar-Icon mit derselben Geometrie/Textur wie in der
     * Welt. {@code mvp} ist die fertige (Ortho × Iso) Icon-Matrix des Icon-Renderers; zusätzlich wird
     * um 90° um die Blockmitte gedreht, damit das Schloss/die Front zur linken sichtbaren Iso-Seite
     * zeigt (sichtbare Iso-Flächen bei ROT_X=30/ROT_Y=225: links=+X, rechts=-Z, oben=+Y).
     *
     * <p>KEINE GL-State-Änderung: nutzt den im Icon-Pass aktiven State (Back-Face-Culling an,
     * Tiefentest aus, Blend an). Das {@code buildBox}-Winding ist konsistent außen=Vorderseite, daher
     * genügt Culling + Zeichenreihenfolge (Korpus→Deckel→Schloss) für die geschlossene Truhe — ein
     * Tiefentest ist unnötig. Wichtig: kein Umschalten von depthFunc/Tiefentest, sonst rekompiliert
     * der Treiber den auch in der Welt genutzten Truhen-Shader pro Frame (GL-Performance-Warnung).
     */
    @Override
    public void renderIcon(Matrix4f mvp) {
        this.iconModel.identity()
                .translate(0.5f, 0.5f, 0.5f).rotateY((float) (Math.PI / 2)).translate(-0.5f, -0.5f, -0.5f);

        this.shader.bind();
        this.shader.setUniformMatrix4f("u_ProjectionView", mvp);
        this.shader.setUniformMatrix4f("u_Model", this.iconModel);
        this.shader.setUniformi("u_Texture", 0);
        this.texture.bind(0);
        this.base.render();
        this.lid.render();    // geschlossen: kein Öffnungswinkel
        this.latch.render();
        this.shader.unbind();
    }

    @Override
    public void dispose() {
        if (this.base != null) this.base.dispose();
        if (this.lid != null) this.lid.dispose();
        if (this.latch != null) this.latch.dispose();
        if (this.texture != null) this.texture.dispose();
        if (this.shader != null) this.shader.dispose();
    }

    /* --- Box-UV-Mesh-Bau (Minecraft-Layout) --- */

    /** Eine Box (Pixel 0..16) mit Box-UV ab Offset (tu,tv) -> interleaved pos3+uv2, 36 Vertices. */
    private static float[] buildBox(int px0, int py0, int pz0, int px1, int py1, int pz1, int tu, int tv) {
        float x0 = px0 / 16f, y0 = py0 / 16f, z0 = pz0 / 16f;
        float x1 = px1 / 16f, y1 = py1 / 16f, z1 = pz1 / 16f;
        int w = px1 - px0, h = py1 - py0, d = pz1 - pz0;
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

    /* Ein Face: 4 Ecken a,b,c,d + UV-Rechteck (tex-px). a=unten-links, b=unten-rechts,
       c=oben-rechts, d=oben-links. Die Engine-Textur sampelt bottom-up, das Vanilla-Box-UV ist
       aber top-down authored — daher wird IN-PLACE vertikal gespiegelt: dieselbe UV-Region
       [v0..v1], nur Ober-/Unterkante getauscht (Unterkante a/b -> v0, Oberkante c/d -> v1).
       Wichtig: nicht 1-v komplementieren — das würde die abgetastete Region verschieben. */
    private static void quad(float[] buf, int[] i,
                             float ax, float ay, float az, float bx, float by, float bz,
                             float cx, float cy, float cz, float dx, float dy, float dz,
                             int tu, int tv, int tw, int th) {
        float u0 = tu / (float) TEX, v0 = tv / (float) TEX;
        float u1 = (tu + tw) / (float) TEX, v1 = (tv + th) / (float) TEX;
        vert(buf, i, ax, ay, az, u0, v0);
        vert(buf, i, bx, by, bz, u1, v0);
        vert(buf, i, cx, cy, cz, u1, v1);
        vert(buf, i, ax, ay, az, u0, v0);
        vert(buf, i, cx, cy, cz, u1, v1);
        vert(buf, i, dx, dy, dz, u0, v1);
    }

    private static void vert(float[] buf, int[] i, float x, float y, float z, float u, float v) {
        buf[i[0]++] = x; buf[i[0]++] = y; buf[i[0]++] = z; buf[i[0]++] = u; buf[i[0]++] = v;
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
