package de.skyengine.graphics.player;

import de.skyengine.core.file.FileHandle;
import de.skyengine.core.file.FileType;
import de.skyengine.game.world.block.BlockTextures;
import de.skyengine.game.world.block.Blocks;
import de.skyengine.game.world.block.model.BakedQuad;
import de.skyengine.game.world.block.model.BlockModels;
import de.skyengine.game.world.block.model.BlockStateModels;
import de.skyengine.game.world.block.state.BlockState;
import de.skyengine.game.world.item.BlockItem;
import de.skyengine.game.world.item.Item;
import de.skyengine.game.world.item.ToolItem;
import de.skyengine.graphics.GlDebug;
import de.skyengine.graphics.shader.Shader;
import de.skyengine.graphics.shader.ShaderProgram;
import de.skyengine.graphics.shader.ShaderType;
import de.skyengine.graphics.texture.TextureArray;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.stb.STBImage;
import org.lwjgl.system.MemoryStack;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.HashMap;
import java.util.Map;

/**
 * Meshes fürs gehaltene Item (First-Person-Hand, Third-Person-Modell, Inventar-Vorschau):
 * Items mit flachem Icon (Tools, Nahrung, Eimer, icon_item, Scheiben) werden wie MCs
 * ItemModelGenerator zu einem 1 px dicken 3D-Sprite EXTRUDIERT (Front/Rückseite + Seitenwände
 * an jeder Alpha-Kante); Block-Items sind Mini-Blockmodelle aus den gebackenen Quads.
 * Die Positionierung übernehmen {@link #drawFirstPerson}/{@link #drawThirdPerson} mit den
 * Vanilla-Display-Transforms (item/generated, item/handheld, block.json).
 *
 * <p>Bake-Vorlagen: {@code ItemIconRenderer.bakeFlat} (Pfad-Auflösung) und
 * {@code EntityRenderer.build} (Block-Quads); Shader = Kopie EntityRenderer (sampler2DArray).
 * Die Texturpfade sind dieselben wie bei den Icons — {@code BlockTextures.layerOf} liefert
 * damit nur bereits registrierte Layer. Culling wird in {@link #bind} deaktiviert
 * (Item-Windings gemischt, Items rotieren frei).
 */
public final class HeldItemMeshes {

    private static final int FLOATS_PER_VERTEX = 9;   // pos3 + texCoord3(u,v,layer) + rgb3

    private ShaderProgram shader;
    private TextureArray textures;

    private record HeldMesh(Mesh mesh, boolean flat, boolean handheld) {}
    private static final HeldMesh EMPTY = new HeldMesh(null, false, false);

    private final Map<Item, HeldMesh> cache = new HashMap<>();
    private final Matrix4f transform = new Matrix4f();
    private boolean cullWasEnabled;

    public void init(TextureArray textures) {
        this.textures = textures;
        this.shader = new ShaderProgram(
                new Shader(VERTEX, ShaderType.VERTEX),
                new Shader(FRAGMENT, ShaderType.FRAGMENT));
    }

    /** Bindet Shader + TextureArray und schaltet Culling aus (Restore in {@link #unbind}). */
    public void bind(Matrix4f projectionView) {
        this.cullWasEnabled = GL11.glIsEnabled(GL11.GL_CULL_FACE);
        GL11.glDisable(GL11.GL_CULL_FACE);
        this.shader.bind();
        this.shader.setUniformMatrix4f("u_ProjectionView", projectionView);
        this.shader.setUniformi("u_Textures", 0);
        this.textures.bind(0);
    }

    public void unbind() {
        this.shader.unbind();
        if (this.cullWasEnabled) GL11.glEnable(GL11.GL_CULL_FACE);
    }

    /**
     * First-Person: {@code base} = Hand-Transformkette in BLOCK-Einheiten. Display-Werte
     * verbatim Vanilla (generated/handheld FP identisch; block.json FP).
     */
    public void drawFirstPerson(Item item, Matrix4f base) {
        HeldMesh held = this.meshFor(item);
        if (held.mesh == null) return;
        this.transform.set(base);
        if (held.flat) {
            this.transform.translate(1.13F / 16F, 3.2F / 16F, 1.13F / 16F)
                    .rotateXYZ(0F, (float) Math.toRadians(-90), (float) Math.toRadians(25))
                    .scale(0.68F);
        } else {
            this.transform.rotateXYZ(0F, (float) Math.toRadians(45), 0F).scale(0.40F);
        }
        this.transform.translate(-0.5F, -0.5F, -0.5F);
        this.shader.setUniformMatrix4f("u_Model", this.transform);
        held.mesh.render();
    }

    /**
     * Third-Person/Vorschau: {@code base} = Anker-Matrix in Modell-PX (nach ItemInHandLayer).
     * Display-Translationen sind px-Werte aus den Vanilla-JSONs, Scale aufs 0..1-Mesh ×16.
     */
    public void drawThirdPerson(Item item, Matrix4f base) {
        HeldMesh held = this.meshFor(item);
        if (held.mesh == null) return;
        this.transform.set(base);
        if (!held.flat) {
            this.transform.translate(0F, 2.5F, 0F)
                    .rotateXYZ((float) Math.toRadians(75), (float) Math.toRadians(45), 0F)
                    .scale(16F * 0.375F);
        } else if (held.handheld) {
            this.transform.translate(0F, 4F, 0.5F)
                    .rotateXYZ(0F, (float) Math.toRadians(-90), (float) Math.toRadians(55))
                    .scale(16F * 0.85F);
        } else {
            this.transform.translate(0F, 3F, 1F)
                    .scale(16F * 0.55F);
        }
        this.transform.translate(-0.5F, -0.5F, -0.5F);
        this.shader.setUniformMatrix4f("u_Model", this.transform);
        held.mesh.render();
    }

    private HeldMesh meshFor(Item item) {
        return this.cache.computeIfAbsent(item, this::bake);
    }

    /**
     * Flaches Icon hat Vorrang (wie das Inventar): Einzel-Pfad → extrudiertes 3D-Sprite,
     * Mehr-Pfad (Tür) → doppelseitiger Quad-Stapel. Sonst Block-Würfel aus dem Default-State.
     */
    private HeldMesh bake(Item item) {
        String[] paths = null;
        int tint = BakedQuad.WHITE;
        if (item instanceof BlockItem bi) {
            String single = BlockStateModels.iconItem(bi.getBlock());
            paths = single != null ? new String[]{single} : BlockStateModels.flatIcon(bi.getBlock());
            tint = bi.getBlock().getTint();
        } else if (item.getIconTexture() != null) {
            paths = new String[]{item.getIconTexture()};
        }
        boolean handheld = item instanceof ToolItem;
        if (paths != null && paths.length == 1) {
            return new HeldMesh(buildExtruded(paths[0], tint), true, handheld);
        }
        if (paths != null && paths.length > 1) {
            return new HeldMesh(buildFlat(paths, tint), true, handheld);
        }
        if (item instanceof BlockItem bi) {
            Mesh mesh = buildBlock(bi.getBlock().getDefaultState());
            if (mesh != null) return new HeldMesh(mesh, false, false);
        }
        return EMPTY;
    }

    /* --- Sprite-Extrusion (MC ItemModelGenerator): 0..1 in x/y, 1 px dick um z=0.5 --- */

    private static final float Z_BACK = 0.5F - 0.5F / 16F;
    private static final float Z_FRONT = 0.5F + 0.5F / 16F;

    private static Mesh buildExtruded(String path, int tint) {
        int layer = BlockTextures.layerOf(path);
        float r = ((tint >> 16) & 0xFF) / 255F;
        float g = ((tint >> 8) & 0xFF) / 255F;
        float b = (tint & 0xFF) / 255F;

        FileHandle file = new FileHandle(path, FileType.RESOURCE);
        int w, h;
        ByteBuffer pixels;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer wB = stack.mallocInt(1), hB = stack.mallocInt(1), cB = stack.mallocInt(1);
            pixels = file.exists() ? STBImage.stbi_load(file.path(), wB, hB, cB, 4) : null;
            if (pixels == null) {
                /* PNG nicht lesbar -> flaches doppelseitiges Quad als Fallback. */
                return buildFlat(new String[]{path}, tint);
            }
            w = wB.get(0);
            h = hB.get(0);
        }

        /* Wände zählen (ein Quad je Alpha-Kante), dann exakt allokieren. */
        int walls = 0;
        for (int py = 0; py < h; py++) {
            for (int px = 0; px < w; px++) {
                if (!opaque(pixels, w, h, px, py)) continue;
                if (!opaque(pixels, w, h, px - 1, py)) walls++;
                if (!opaque(pixels, w, h, px + 1, py)) walls++;
                if (!opaque(pixels, w, h, px, py - 1)) walls++;
                if (!opaque(pixels, w, h, px, py + 1)) walls++;
            }
        }

        float[] data = new float[(2 + walls) * 6 * FLOATS_PER_VERTEX];
        int p = 0;
        /* Vorderseite (volle UV; Transparenz macht der discard) + gespiegelte Rückseite.
           Mesh-y 0 = unten = Textur-v 1 (Pixelzeile h-1). */
        p = quad(data, p,
                0, 0, Z_FRONT, 1, 0, Z_FRONT, 1, 1, Z_FRONT, 0, 1, Z_FRONT,
                0, 1, 1, 1, 1, 0, 0, 0, layer, r, g, b);
        p = quad(data, p,
                1, 0, Z_BACK, 0, 0, Z_BACK, 0, 1, Z_BACK, 1, 1, Z_BACK,
                1, 1, 0, 1, 0, 0, 1, 0, layer, r, g, b);

        for (int py = 0; py < h; py++) {
            for (int px = 0; px < w; px++) {
                if (!opaque(pixels, w, h, px, py)) continue;
                float x0 = px / (float) w, x1 = (px + 1) / (float) w;
                float yT = 1F - py / (float) h, yB = 1F - (py + 1) / (float) h;
                float u = (px + 0.5F) / w, v = (py + 0.5F) / h;   // Kanten-Farbe = Texel-Zentrum
                if (!opaque(pixels, w, h, px - 1, py)) {
                    p = wall(data, p, x0, yB, Z_BACK, x0, yB, Z_FRONT, x0, yT, Z_FRONT, x0, yT, Z_BACK, u, v, layer, r, g, b);
                }
                if (!opaque(pixels, w, h, px + 1, py)) {
                    p = wall(data, p, x1, yB, Z_FRONT, x1, yB, Z_BACK, x1, yT, Z_BACK, x1, yT, Z_FRONT, u, v, layer, r, g, b);
                }
                if (!opaque(pixels, w, h, px, py - 1)) {
                    p = wall(data, p, x0, yT, Z_FRONT, x1, yT, Z_FRONT, x1, yT, Z_BACK, x0, yT, Z_BACK, u, v, layer, r, g, b);
                }
                if (!opaque(pixels, w, h, px, py + 1)) {
                    p = wall(data, p, x0, yB, Z_BACK, x1, yB, Z_BACK, x1, yB, Z_FRONT, x0, yB, Z_FRONT, u, v, layer, r, g, b);
                }
            }
        }
        STBImage.stbi_image_free(pixels);
        return new Mesh(data);
    }

    private static boolean opaque(ByteBuffer pixels, int w, int h, int px, int py) {
        if (px < 0 || py < 0 || px >= w || py >= h) return false;
        return (pixels.get((py * w + px) * 4 + 3) & 0xFF) > 0;
    }

    /** Quad mit per-Vertex-UV (Front/Rückseite). */
    private static int quad(float[] d, int p,
                            float ax, float ay, float az, float bx, float by, float bz,
                            float cx, float cy, float cz, float dx, float dy, float dz,
                            float au, float av, float bu, float bv, float cu, float cv, float du, float dv,
                            int layer, float r, float g, float b) {
        p = vert(d, p, ax, ay, az, au, av, layer, r, g, b);
        p = vert(d, p, bx, by, bz, bu, bv, layer, r, g, b);
        p = vert(d, p, cx, cy, cz, cu, cv, layer, r, g, b);
        p = vert(d, p, ax, ay, az, au, av, layer, r, g, b);
        p = vert(d, p, cx, cy, cz, cu, cv, layer, r, g, b);
        p = vert(d, p, dx, dy, dz, du, dv, layer, r, g, b);
        return p;
    }

    /** Seitenwand-Quad mit konstantem UV (Texel-Zentrum der Kante). */
    private static int wall(float[] d, int p,
                            float ax, float ay, float az, float bx, float by, float bz,
                            float cx, float cy, float cz, float dx, float dy, float dz,
                            float u, float v, int layer, float r, float g, float b) {
        return quad(d, p, ax, ay, az, bx, by, bz, cx, cy, cz, dx, dy, dz,
                u, v, u, v, u, v, u, v, layer, r, g, b);
    }

    /** Quad-Stapel x/y 0..1 bei z=0.5, jede Lage vorder- UND rückseitig (Tür = 2 Lagen). */
    private static Mesh buildFlat(String[] paths, int tint) {
        float r = ((tint >> 16) & 0xFF) / 255F;
        float g = ((tint >> 8) & 0xFF) / 255F;
        float b = (tint & 0xFF) / 255F;
        int n = paths.length;
        float[] data = new float[n * 12 * FLOATS_PER_VERTEX];
        int p = 0;
        for (int i = 0; i < n; i++) {
            int layer = BlockTextures.layerOf(paths[i]);
            float ya = (float) i / n, yb = (float) (i + 1) / n;
            p = quad(data, p,
                    0, ya, 0.5F, 1, ya, 0.5F, 1, yb, 0.5F, 0, yb, 0.5F,
                    0, 1, 1, 1, 1, 0, 0, 0, layer, r, g, b);
            p = quad(data, p,
                    1, ya, 0.5F, 0, ya, 0.5F, 0, yb, 0.5F, 1, yb, 0.5F,
                    1, 1, 0, 1, 0, 0, 1, 0, layer, r, g, b);
        }
        return new Mesh(data);
    }

    private static int vert(float[] d, int p, float x, float y, float z, float u, float v, int layer, float r, float g, float b) {
        d[p++] = x; d[p++] = y; d[p++] = z; d[p++] = u; d[p++] = v; d[p++] = layer;
        d[p++] = r; d[p++] = g; d[p++] = b;
        return p;
    }

    /** Backt die Quads des States in ein interleaved Mesh (Kopie EntityRenderer.build). */
    private static Mesh buildBlock(BlockState state) {
        BakedQuad[] quads = state.getModel();
        if (quads == null || quads.length == 0) {
            /* BER-Blöcke (Truhe) haben kein statisches Modell — Platzhalter wie beim Icon-Fallback. */
            BlockState fallback = Blocks.getState(Blocks.OAK_PLANKS);
            quads = fallback.getModel();
            if (quads == null || quads.length == 0) return null;
        }
        BakedQuad[] overlay = state.getOverlay();
        if (overlay.length > 0) quads = BlockModels.concat(quads, overlay);

        int verts = 0;
        for (BakedQuad q : quads) verts += q.vertices().length / 5;
        if (verts == 0) return null;

        float[] data = new float[verts * FLOATS_PER_VERTEX];
        int p = 0;
        for (BakedQuad q : quads) {
            float[] v = q.vertices();
            int n = v.length / 5;
            int tint = q.tint();
            float r = q.brightness() * ((tint >> 16) & 0xFF) / 255F;
            float g = q.brightness() * ((tint >> 8) & 0xFF) / 255F;
            float b = q.brightness() * (tint & 0xFF) / 255F;
            for (int i = 0; i < n; i++) {
                data[p++] = v[i * 5];
                data[p++] = v[i * 5 + 1];
                data[p++] = v[i * 5 + 2];
                data[p++] = v[i * 5 + 3];
                data[p++] = v[i * 5 + 4];
                data[p++] = q.textureLayer();
                data[p++] = r;
                data[p++] = g;
                data[p++] = b;
            }
        }
        return new Mesh(data);
    }

    public void dispose() {
        for (HeldMesh m : this.cache.values()) if (m.mesh != null) m.mesh.dispose();
        this.cache.clear();
        if (this.shader != null) this.shader.dispose();
    }

    /* --- kleine VAO/VBO-Hülle (Layout wie EntityRenderer) --- */
    private static final class Mesh {
        private final int vao, vbo, count;

        Mesh(float[] data) {
            this.count = data.length / FLOATS_PER_VERTEX;
            this.vao = GL30.glGenVertexArrays();
            this.vbo = GL15.glGenBuffers();
            GL30.glBindVertexArray(this.vao);
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, this.vbo);
            GL15.glBufferData(GL15.GL_ARRAY_BUFFER, data, GL15.GL_STATIC_DRAW);
            GlDebug.labelBuffer(this.vbo, "HeldItemMeshes Mesh-VBO");
            int stride = FLOATS_PER_VERTEX * Float.BYTES;
            GL20.glVertexAttribPointer(0, 3, GL11.GL_FLOAT, false, stride, 0);
            GL20.glVertexAttribPointer(1, 3, GL11.GL_FLOAT, false, stride, 3 * Float.BYTES);
            GL20.glVertexAttribPointer(2, 3, GL11.GL_FLOAT, false, stride, 6 * Float.BYTES);
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
        layout(location = 1) in vec3 a_texCoord;
        layout(location = 2) in vec3 a_color;
        uniform mat4 u_ProjectionView;
        uniform mat4 u_Model;
        out vec3 v_texCoord;
        out vec3 v_color;
        void main() {
            v_texCoord = a_texCoord;
            v_color = a_color;
            gl_Position = u_ProjectionView * u_Model * vec4(a_position, 1.0);
        }
        """;

    private static final String FRAGMENT = """
        #version 460 core
        in vec3 v_texCoord;
        in vec3 v_color;
        uniform sampler2DArray u_Textures;
        out vec4 fragColor;
        void main() {
            vec4 c = texture(u_Textures, v_texCoord);
            if (c.a < 0.5) discard;
            fragColor = vec4(c.rgb * v_color, c.a);
        }
        """;
}
