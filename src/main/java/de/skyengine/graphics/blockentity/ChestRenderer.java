package de.skyengine.graphics.blockentity;

import de.skyengine.core.file.FileHandle;
import de.skyengine.core.file.FileType;
import de.skyengine.game.world.block.Direction;
import de.skyengine.game.world.block.entity.BlockEntity;
import de.skyengine.game.world.block.entity.ChestBlockEntity;
import de.skyengine.game.world.block.state.ChestType;
import de.skyengine.graphics.GlDebug;
import de.skyengine.graphics.GlState;
import de.skyengine.graphics.camera.Camera;
import de.skyengine.graphics.gui.ItemIconLighting;
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
    private static final int FLOATS_PER_VERTEX = 8;  // pos3 + uv2 + normal3
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

    /* Doppeltruhen-Hälften: eigene Texturen und Geometrie (15 statt 14 breit). */
    private Texture textureLeft, textureRight;
    private Mesh baseLeft, lidLeft, latchLeft;
    private Mesh baseRight, lidRight, latchRight;

    private final Matrix4f model = new Matrix4f();
    private final Matrix4f iconModel = new Matrix4f();
    private final Matrix4f normalRot = new Matrix4f();

    @Override
    public void init() {
        this.shader = new ShaderProgram(
                new Shader(VERTEX, ShaderType.VERTEX),
                new Shader(FRAGMENT, ShaderType.FRAGMENT));
        this.texture = new Texture(new FileHandle("game/textures/entity/chest/normal.png", FileType.RESOURCE), false);

        this.base = new Mesh(buildBox(1, 0, 1, 15, 10, 15, 0, 19));   // Korpus (0..10)
        this.lid = new Mesh(buildBox(1, 9, 1, 15, 14, 15, 0, 0));     // Deckel (9..14, MC-Höhe)
        this.latch = new Mesh(buildBox(7, 7, 15, 9, 11, 16, 0, 0));   // Schloss vorne (UV wie Vanilla-Knob)

        /* Doppeltruhe: jede Hälfte ist 15 statt 14 breit und ragt bis an die Blockgrenze zur
           Partnerhälfte — zusammen ergibt das einen durchgehenden Korpus ohne Naht. Das Schloss
           ist nur 1 px breit und sitzt AN der Naht; beide Hälften zusammen bilden das mittige
           2-px-Schloss der Vanilla-Doppeltruhe.
           Zur Richtung: unsere kanonische Modellfront ist +Z OHNE die 180°-Drehung, die Vanilla
           anwendet — deshalb ragen die Hälften spiegelbildlich zu Vanillas Zahlen. LEFT hat den
           Partner bei facing.rotateYCW() (bei SOUTH also -X), reicht also von 0 bis 15. */
        this.textureLeft = new Texture(new FileHandle("game/textures/entity/chest/normal_left.png", FileType.RESOURCE), false);
        this.textureRight = new Texture(new FileHandle("game/textures/entity/chest/normal_right.png", FileType.RESOURCE), false);

        this.baseLeft = new Mesh(buildBox(0, 0, 1, 15, 10, 15, 0, 19));
        this.lidLeft = new Mesh(buildBox(0, 9, 1, 15, 14, 15, 0, 0));
        this.latchLeft = new Mesh(buildBox(0, 7, 15, 1, 11, 16, 0, 0));

        this.baseRight = new Mesh(buildBox(1, 0, 1, 16, 10, 15, 0, 19));
        this.lidRight = new Mesh(buildBox(1, 9, 1, 16, 14, 15, 0, 0));
        this.latchRight = new Mesh(buildBox(15, 7, 15, 16, 11, 16, 0, 0));

        /* Uniform-Locations einmalig cachen (Muster ChunkRenderer) — der String-Weg machte
           pro Truhe pro Frame ~9 HashMap-Lookups. Die Helligkeiten werden weiterhin pro
           render() gesetzt: die Icon-/Hand-Pfade unten überschreiben sie mit eigenen Werten.
           Nur u_Texture (immer Unit 0, in allen Pfaden) ist einmalig gesetzt. */
        this.locProjectionView = this.shader.getUniformLocation("u_ProjectionView");
        this.locLight = this.shader.getUniformLocation("u_Light");
        this.locNormalRot = this.shader.getUniformLocation("u_NormalRot");
        this.locModel = this.shader.getUniformLocation("u_Model");
        this.locTopBrightness = this.shader.getUniformLocation("u_TopBrightness");
        this.locZBrightness = this.shader.getUniformLocation("u_ZBrightness");
        this.locSideBrightness = this.shader.getUniformLocation("u_SideBrightness");
        this.locIconLighting = this.shader.getUniformLocation("u_IconLighting");
        this.shader.bind();
        this.shader.setUniformi("u_Texture", 0);
        this.shader.setUniformi(this.locIconLighting, 0);
        this.shader.unbind();
    }

    private int locProjectionView, locLight, locNormalRot, locModel,
            locTopBrightness, locZBrightness, locSideBrightness, locIconLighting;

    @Override
    public void render(BlockEntity be, Camera camera, float partialTick, float light) {
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

        /* Einzeltruhe oder eine der beiden Doppeltruhen-Hälften. */
        ChestType type = chest.getChestType();
        Texture tex = switch (type) {
            case LEFT -> this.textureLeft;
            case RIGHT -> this.textureRight;
            case SINGLE -> this.texture;
        };
        Mesh baseMesh = switch (type) {
            case LEFT -> this.baseLeft;
            case RIGHT -> this.baseRight;
            case SINGLE -> this.base;
        };
        Mesh lidMesh = switch (type) {
            case LEFT -> this.lidLeft;
            case RIGHT -> this.lidRight;
            case SINGLE -> this.lid;
        };
        Mesh latchMesh = switch (type) {
            case LEFT -> this.latchLeft;
            case RIGHT -> this.latchRight;
            case SINGLE -> this.latch;
        };

        boolean cull = GlState.isCullFaceEnabled();
        GlState.disableCullFace();

        this.shader.bind();
        /* Welt-Truhe: normale MC-Helligkeit pro Achse (oben/Nord-Süd/West-Ost); das Himmelslicht
           der Zelle kommt im Shader einmal obendrauf — dieselbe Kombination wie beim Terrain
           (FACE_BRIGHTNESS × Licht). Pro render() gesetzt, weil die Icon-/Hand-Pfade dieselben
           Uniforms mit eigenen Werten überschreiben. */
        this.shader.setUniformMatrix4f(this.locProjectionView, camera.getProjectionViewMatrix());
        this.shader.setUniformf(this.locTopBrightness, 1.0f);
        this.shader.setUniformf(this.locZBrightness, 0.8f);
        this.shader.setUniformf(this.locSideBrightness, 0.6f);
        this.shader.setUniformi(this.locIconLighting, 0);
        this.shader.setUniformf(this.locLight, light);
        tex.bind(0);

        /* Normalen nur um die Facing-Achse drehen (ohne Deckel-Klappung), damit das Richtungs-
           Shading weltachsen-fest bleibt und sich beim Öffnen nicht verschiebt. */
        this.normalRot.identity().rotateY(facingY);
        this.shader.setUniformMatrix4f(this.locNormalRot, this.normalRot);

        /* Korpus: nur Facing-Drehung um die vertikale Blockmitte (0.5, *, 0.5). */
        this.model.translation(ox, oy, oz)
                .translate(0.5f, 0f, 0.5f).rotateY(facingY).translate(-0.5f, 0f, -0.5f);
        this.shader.setUniformMatrix4f(this.locModel, this.model);
        baseMesh.render();

        /* Deckel + Schloss: Facing-Drehung, dann Aufklappen um die hintere Scharnierkante. */
        this.model.translation(ox, oy, oz)
                .translate(0.5f, 0f, 0.5f).rotateY(facingY).translate(-0.5f, 0f, -0.5f)
                .translate(0, HINGE_Y, HINGE_Z)
                .rotateX(-angle)
                .translate(0, -HINGE_Y, -HINGE_Z);
        this.shader.setUniformMatrix4f(this.locModel, this.model);
        lidMesh.render();
        latchMesh.render();

        this.shader.unbind();
        if (cull) GlState.enableCullFace();
    }

    @Override
    public boolean hasIcon() {
        return true;
    }

    /**
     * Zeichnet die GESCHLOSSENE Truhe als Inventar-Icon mit derselben Geometrie/Textur wie in der
     * Welt. {@code mvp} enthaelt bereits den exakten {@code template_chest}-GUI-Transform aus
     * {@code chest_item.json}; eine weitere Modellrotation wuerde die Front wieder um 90 Grad
     * verdrehen.
     *
     * <p>KEINE GL-State-Änderung: nutzt den im Icon-Pass aktiven State (Back-Face-Culling an,
     * Tiefentest aus, Blend an). Das {@code buildBox}-Winding ist konsistent außen=Vorderseite, daher
     * genügt Culling + Zeichenreihenfolge (Korpus→Deckel→Schloss) für die geschlossene Truhe — ein
     * Tiefentest ist unnötig. Wichtig: kein Umschalten von depthFunc/Tiefentest, sonst rekompiliert
     * der Treiber den auch in der Welt genutzten Truhen-Shader pro Frame (GL-Performance-Warnung).
     */
    @Override
    public void renderIcon(Matrix4f mvp, Matrix4f itemTransform) {
        this.iconModel.identity();

        this.shader.bind();
        this.shader.setUniformMatrix4f("u_ProjectionView", mvp);
        this.shader.setUniformMatrix4f("u_Model", this.iconModel);
        /* Der Display-Transform dreht Geometrie und Normalen gemeinsam wie in Vanilla. */
        this.normalRot.set(itemTransform);
        this.shader.setUniformMatrix4f("u_NormalRot", this.normalRot);
        /* Icon: dieselben pro-Achsen-Schrauben wie die Würfel-Icons (oben/Nord-Süd/West-Ost). */
        this.shader.setUniformi(this.locIconLighting, 1);
        ItemIconLighting.apply3D(this.shader);
        /* GUI: NIE abdunkeln — derselbe Shader wie im Welt-Pass, also explizit zurücksetzen. */
        this.shader.setUniformf("u_Light", 1.0f);
        this.shader.setUniformi("u_Texture", 0);
        this.texture.bind(0);
        this.base.render();
        this.lid.render();    // geschlossen: kein Öffnungswinkel
        this.latch.render();
        this.shader.unbind();
    }

    /**
     * Zeichnet die GESCHLOSSENE Truhe als gehaltenes Item. {@code mvp} kommt fertig aus
     * {@code HeldItemMeshes} (ProjView × Hand-/Display-Transform) — daher u_Model = Identität
     * (keine Icon-Vorrotation) und u_NormalRot = Identität (Richtungs-Shading truhen-lokal:
     * Deckel hell, Front/Rück 0.8, Seiten 0.6 — gleicher Look wie die Welt-Truhe).
     * Wie bei {@link #renderIcon}: KEIN Depth-/Cull-Umschalten (Shader-Rekompilierungs-Warnung).
     */
    @Override
    public void renderHeld(Matrix4f mvp, float light) {
        this.iconModel.identity();

        this.shader.bind();
        this.shader.setUniformMatrix4f("u_ProjectionView", mvp);
        this.shader.setUniformMatrix4f("u_Model", this.iconModel);
        this.normalRot.identity();
        this.shader.setUniformMatrix4f("u_NormalRot", this.normalRot);
        this.shader.setUniformi(this.locIconLighting, 0);
        /* Hand: normale Welt-Helligkeit statt der dunkleren Icon-Schrauben; das Licht der Zelle
           kommt im Shader dazu. In der Inventar-Vorschau reicht der Aufrufer light = 1.0 durch. */
        this.shader.setUniformf("u_TopBrightness", 1.0f);
        this.shader.setUniformf("u_ZBrightness", 0.8f);
        this.shader.setUniformf("u_SideBrightness", 0.6f);
        this.shader.setUniformf("u_Light", light);
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
        if (this.baseLeft != null) this.baseLeft.dispose();
        if (this.lidLeft != null) this.lidLeft.dispose();
        if (this.latchLeft != null) this.latchLeft.dispose();
        if (this.baseRight != null) this.baseRight.dispose();
        if (this.lidRight != null) this.lidRight.dispose();
        if (this.latchRight != null) this.latchRight.dispose();
        if (this.texture != null) this.texture.dispose();
        if (this.textureLeft != null) this.textureLeft.dispose();
        if (this.textureRight != null) this.textureRight.dispose();
        if (this.shader != null) this.shader.dispose();
    }

    /* --- Box-UV-Mesh-Bau (Minecraft-Layout) --- */

    /** Eine Box (Pixel 0..16) mit Box-UV ab Offset (tu,tv) -> interleaved pos3+uv2+normal3, 36 Vertices. */
    private static float[] buildBox(int px0, int py0, int pz0, int px1, int py1, int pz1, int tu, int tv) {
        float x0 = px0 / 16f, y0 = py0 / 16f, z0 = pz0 / 16f;
        float x1 = px1 / 16f, y1 = py1 / 16f, z1 = pz1 / 16f;
        int w = px1 - px0, h = py1 - py0, d = pz1 - pz0;
        float[] buf = new float[36 * FLOATS_PER_VERTEX];
        int[] i = {0};

        /* Lokale Face-Normale je Face; das Richtungs-Shading wird daraus im Shader (weltachsen-fest)
           bestimmt, damit es unabhängig von der Facing-Drehung zu den Nachbarblöcken passt. */
        quad(buf, i, x0, y1, z1, x1, y1, z1, x1, y1, z0, x0, y1, z0, tu + d + w,     tv,     w, d,  0,  1,  0, false); // up (+y)
        quad(buf, i, x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1, tu + d,         tv,     w, d,  0, -1,  0, false); // down (-y)
        quad(buf, i, x1, y0, z0, x0, y0, z0, x0, y1, z0, x1, y1, z0, tu + d,         tv + d, w, h,  0,  0, -1, true);  // north (-z)
        quad(buf, i, x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1, tu + d + w + d, tv + d, w, h,  0,  0,  1, true);  // south (+z)
        quad(buf, i, x0, y0, z0, x0, y0, z1, x0, y1, z1, x0, y1, z0, tu,             tv + d, d, h, -1,  0,  0, false); // west (-x)
        quad(buf, i, x1, y0, z1, x1, y0, z0, x1, y1, z0, x1, y1, z1, tu + d + w,     tv + d, d, h,  1,  0,  0, false); // east (+x)
        return buf;
    }

    /* Ein Face: 4 Ecken a,b,c,d + UV-Rechteck (tex-px) + Face-Normale (nx,ny,nz). a=unten-links,
       b=unten-rechts, c=oben-rechts, d=oben-links. Die Engine-Textur sampelt bottom-up, das
       Vanilla-Box-UV ist aber top-down authored — daher wird IN-PLACE vertikal gespiegelt: dieselbe
       UV-Region [v0..v1], nur Ober-/Unterkante getauscht (Unterkante a/b -> v0, Oberkante c/d -> v1).
       Wichtig: nicht 1-v komplementieren — das würde die abgetastete Region verschieben.

       {@code flipU} dreht die HORIZONTALE Laufrichtung innerhalb derselben Region um. Nord- und
       Süd-Face brauchen das: Vanilla wickelt die Box als durchgehendes Band ab, deshalb läuft u auf
       Vorder- und Rückseite entgegen der x-Richtung der Seitenflächen. Bei der symmetrischen
       Einzeltruhe ist der Unterschied unsichtbar — bei den Doppeltruhen-Hälften landete dadurch der
       dunkle Außenrand an der Naht statt außen (in normal_left.png nachgemessen: die Nahtkante ist
       vorn bei HOHEM u, hinten bei NIEDRIGEM u, während die Seiten- und Deckelflächen zur
       x-Richtung passen). NICHT über die Eckreihenfolge lösen — das drehte das Winding, auf das
       der Icon-Pfad mit aktivem Back-Face-Culling angewiesen ist. */
    private static void quad(float[] buf, int[] i,
                             float ax, float ay, float az, float bx, float by, float bz,
                             float cx, float cy, float cz, float dx, float dy, float dz,
                             int tu, int tv, int tw, int th, float nx, float ny, float nz,
                             boolean flipU) {
        float u0 = tu / (float) TEX, v0 = tv / (float) TEX;
        float u1 = (tu + tw) / (float) TEX, v1 = (tv + th) / (float) TEX;
        float uL = flipU ? u1 : u0, uR = flipU ? u0 : u1;
        vert(buf, i, ax, ay, az, uL, v0, nx, ny, nz);
        vert(buf, i, bx, by, bz, uR, v0, nx, ny, nz);
        vert(buf, i, cx, cy, cz, uR, v1, nx, ny, nz);
        vert(buf, i, ax, ay, az, uL, v0, nx, ny, nz);
        vert(buf, i, cx, cy, cz, uR, v1, nx, ny, nz);
        vert(buf, i, dx, dy, dz, uL, v1, nx, ny, nz);
    }

    private static void vert(float[] buf, int[] i, float x, float y, float z, float u, float v,
                             float nx, float ny, float nz) {
        buf[i[0]++] = x; buf[i[0]++] = y; buf[i[0]++] = z;
        buf[i[0]++] = u; buf[i[0]++] = v;
        buf[i[0]++] = nx; buf[i[0]++] = ny; buf[i[0]++] = nz;
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
            GlDebug.labelBuffer(this.vbo, "ChestRenderer Mesh-VBO");
            int stride = FLOATS_PER_VERTEX * Float.BYTES;
            GL20.glVertexAttribPointer(0, 3, GL11.GL_FLOAT, false, stride, 0);
            GL20.glVertexAttribPointer(1, 2, GL11.GL_FLOAT, false, stride, 3 * Float.BYTES);
            GL20.glVertexAttribPointer(2, 3, GL11.GL_FLOAT, false, stride, 5 * Float.BYTES);
            GL20.glEnableVertexAttribArray(0);
            GL20.glEnableVertexAttribArray(1);
            GL20.glEnableVertexAttribArray(2);
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
        layout(location = 2) in vec3 a_normal;
        uniform mat4 u_ProjectionView;
        uniform mat4 u_Model;
        uniform mat4 u_NormalRot;
        out vec2 v_uv;
        out vec3 v_normal;
        void main() {
            v_uv = a_uv;
            v_normal = normalize(transpose(inverse(mat3(u_NormalRot))) * a_normal);
            gl_Position = u_ProjectionView * u_Model * vec4(a_position, 1.0);
        }
        """;

    private static final String FRAGMENT = """
        #version 460 core
        in vec2 v_uv;
        in vec3 v_normal;
        uniform sampler2D u_Texture;
        uniform float u_TopBrightness;
        uniform float u_ZBrightness;
        uniform float u_SideBrightness;
        uniform int u_IconLighting;
        uniform vec3 u_ItemLight0;
        uniform vec3 u_ItemLight1;
        /* Licht der Zelle, Himmel + Block (ChunkRenderer.lightFactor); 1.0 = voll hell bzw. GUI. */
        uniform float u_Light;
        layout(location = 0) out vec4 fragColor;
        void main() {
            vec4 c = texture(u_Texture, v_uv);
            if (c.a < 0.5) discard;
            // Richtungs-Shading aus der weltgedrehten Flaechen-Normale. Top/N-S/W-E sind als Uniforms
            // variabel (Welt: 1.0/0.8/0.6, Icon: eigene Schrauben); die Unterseite ist eine Konstante
            // (0.5 wie FACE_BRIGHTNESS[1]) und an der gekippten Truhe in der HAND sehr wohl sichtbar.
            vec3 n = normalize(v_normal);
            float br;
            if (u_IconLighting != 0) {
                float diffuse = max(0.0, dot(n, u_ItemLight0)) + max(0.0, dot(n, u_ItemLight1));
                br = min(1.0, diffuse * 0.6 + 0.4);
            } else {
                br = (n.y > 0.5) ? u_TopBrightness
                   : (n.y < -0.5) ? 0.5
                   : (abs(n.z) > 0.5) ? u_ZBrightness
                   : u_SideBrightness;
            }
            /* Zellenlicht bewusst EINMAL ganz am Ende statt an jede Helligkeit einzeln: so kann
               keine Flaeche es verpassen. Genau daran ist die Unterseite schon einmal
               vorbeigelaufen, weil sie als einzige kein Uniform ist. */
            fragColor = vec4(c.rgb * br * u_Light, c.a);
        }
        """;
}
