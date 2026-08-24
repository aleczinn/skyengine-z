package de.skyengine.graphics.gui;

import de.skyengine.core.EngineProperties;
import de.skyengine.core.SkyEngine;
import de.skyengine.game.world.block.BlockTextures;
import de.skyengine.game.world.block.Blocks;
import de.skyengine.game.world.block.entity.BlockEntityType;
import de.skyengine.game.world.block.model.BakedQuad;
import de.skyengine.game.world.block.model.BlockModels;
import de.skyengine.game.world.block.model.BlockStateModels;
import de.skyengine.game.world.block.model.ModelLoader;
import de.skyengine.game.world.block.state.BlockState;
import de.skyengine.game.world.item.BlockItem;
import de.skyengine.game.world.item.Item;
import de.skyengine.game.world.item.ItemStack;
import de.skyengine.graphics.GlDebug;
import de.skyengine.graphics.blockentity.BlockEntityRenderDispatcher;
import de.skyengine.graphics.blockentity.BlockEntityRenderer;
import de.skyengine.graphics.shader.Shader;
import de.skyengine.graphics.shader.ShaderProgram;
import de.skyengine.graphics.shader.ShaderType;
import de.skyengine.graphics.texture.TextureArray;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

import java.util.HashMap;
import java.util.Map;

/**
 * Rendert Item-Icons als kleine isometrische 3D-Block-Würfel (wie Minecraft) in die GUI.
 * Nutzt die bereits gebackenen Block-Quads (inkl. Texturlayer + Helligkeit pro Fläche) und das
 * vorhandene Block-{@link TextureArray}. Das Mesh je Block wird einmal gebacken und gecacht.
 *
 * <p>3D-Icons laufen MIT Tiefentest: mehrteilige, sich durchdringende Modelle (Zaun: Balken
 * stecken in den Pfosten) sind über die Zeichenreihenfolge allein nicht korrekt darstellbar.
 * Vor jedem Icon wird der Tiefenpuffer geleert — dadurch liegt das zuletzt gezeichnete
 * Cursor-Icon weiterhin über den Slot-Icons. Die Ortho-Projektion folgt dem aktiven
 * Tiefen-Modus der Engine (Reversed-Z: nah→1 + GL_GREATER, sonst nah→0 + GL_LESS).
 */
public final class ItemIconRenderer {

    /* Fallback fuer Modelle ohne display.gui. Normale Blockmodelle erben ihre exakten Werte aus
       block/block.json; Sondermodelle wie Zaun und Zauntor ueberschreiben sie wie in Vanilla. */
    private static final float ROT_X = 30f;
    private static final float ROT_Y = 225f;
    /**
     * Würfelkante als Anteil der Slot-Pixelgröße — 0.625 ist MCs {@code gui}-Display-Scale und
     * KEIN frei wählbarer Geschmackswert: unter Rx(30)·Ry(135) ist ein Einheitswürfel
     * {@code 2·(0.5·cos30 + √½·sin30) = 1.5731} hoch, das Icon also {@code 0.625 · 1.5731 =
     * 0.983 × Slot} — es passt gerade noch hinein. Der frühere Wert 0.66 ergab 1.038 und ließ
     * jeden Block oben und unten ~0,3 px über den Slotrahmen ragen.
     */
    private static final float ICON_SCALE = 0.625f;

    private ShaderProgram shader;
    private TextureArray textures;
    /** Für Block-Entity-Icons (z.B. Truhe): liefert den Renderer mit echtem Modell + 2D-Textur. */
    private BlockEntityRenderDispatcher blockEntityRenderers;

    private final Matrix4f proj = new Matrix4f();
    private final Matrix4f model = new Matrix4f();
    private final Matrix4f mvp = new Matrix4f();

    private final Map<Item, Mesh> cache = new HashMap<>();
    /** Flache 2D-Icons (Glasscheibe, Tür) — wie MCs Item-Sprites; null-Mesh = Block ist nicht flach. */
    private final Map<Item, FlatIcon> flatCache = new HashMap<>();

    public void init(TextureArray textures, BlockEntityRenderDispatcher blockEntityRenderers) {
        this.textures = textures;
        this.blockEntityRenderers = blockEntityRenderers;
        this.shader = new ShaderProgram(
                new Shader(VERTEX, ShaderType.VERTEX),
                new Shader(FRAGMENT, ShaderType.FRAGMENT));
    }

    /** Beginnt den Icon-Pass: Shader, Y-up-Ortho-Projektion und GL-State. (Virtueller GUI-Raum.) */
    public void begin(float vW, float vH) {
        /* Near/Far passend zum aktiven Tiefen-Modus: Reversed-Z (global GL_GREATER, clearDepth 0)
           braucht nah→1/fern→0, Standard-Z (GL_LESS, clearDepth 1) nah→0/fern→1. */
        if (SkyEngine.get().getWindow().getProperties().isUseInverseDepth()) {
            this.proj.identity().ortho(0, vW, 0, vH, 2000, -2000, true);
        } else {
            this.proj.identity().ortho(0, vW, 0, vH, -2000, 2000, true);
        }
        this.shader.bind();
        this.shader.setUniformi("u_Textures", 0);
        this.textures.bind(0);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glDisable(GL11.GL_DEPTH_TEST);
    }

    /**
     * Zeichnet das Icon zentriert auf (centerX, centerY) in Bildschirm-Pixeln (Ursprung oben links).
     * {@code slotPixelSize} ist die Slot-Innengröße; die Würfelkante ergibt sich daraus * ICON_SCALE.
     */
    public void drawIcon(ItemStack stack, float centerX, float centerY, float slotPixelSize, float vH) {
        if (stack == null || stack.isEmpty()) return;

        /* Flaches 2D-Icon (Glasscheibe = ein Quad, Tür = zwei gestapelte Quads als Mini-Tür). */
        FlatIcon flat = this.flatCache.computeIfAbsent(stack.getItem(), this::bakeFlat);
        if (flat.mesh != null) {
            drawFlat(flat, centerX, centerY, slotPixelSize, vH);
            return;
        }

        float s = slotPixelSize * ICON_SCALE;
        float cyUp = vH - centerY; // Ortho ist Y-up, Slotkoordinaten sind Y-down
        ModelLoader.Display guiDisplay = guiDisplayFor(stack.getItem());
        if (guiDisplay != null) {
            this.model.identity()
                    .translate(centerX, cyUp, 0)
                    .translate(guiDisplay.translation()[0] * slotPixelSize / 16F,
                               guiDisplay.translation()[1] * slotPixelSize / 16F,
                               guiDisplay.translation()[2] * slotPixelSize / 16F)
                    .rotateXYZ((float) Math.toRadians(guiDisplay.rotation()[0]),
                               (float) Math.toRadians(guiDisplay.rotation()[1]),
                               (float) Math.toRadians(guiDisplay.rotation()[2]))
                    .scale(slotPixelSize * guiDisplay.scale()[0],
                           slotPixelSize * guiDisplay.scale()[1],
                           slotPixelSize * guiDisplay.scale()[2])
                    .translate(-0.5F, -0.5F, -0.5F);
        } else {
            this.model.identity()
                    .translate(centerX, cyUp, 0)
                    .scale(s, s, s)
                    .rotateX((float) Math.toRadians(ROT_X))
                    .rotateY((float) Math.toRadians(ROT_Y))
                    .translate(-0.5f, -0.5f, -0.5f);
        }
        this.proj.mul(this.model, this.mvp);

        /* Block-Entity-Icon (z.B. Truhe): echtes BER-Modell + dessen 2D-Textur statt Block-Quads. */
        BlockEntityRenderer custom = customIconFor(stack.getItem());
        if (custom != null) {
            custom.renderIcon(this.mvp, this.model);
            /* Shader/Textur des Würfel-Pfads für nachfolgende Icons wiederherstellen. */
            this.shader.bind();
            this.shader.setUniformi("u_Textures", 0);
            this.textures.bind(0);
            return;
        }

        Mesh mesh = this.cache.computeIfAbsent(stack.getItem(), this::bake);
        if (mesh == null || mesh.count == 0) return;
        if (guiDisplay == null && mesh.fit != 1F) {
            this.model.identity()
                    .translate(centerX, cyUp, 0)
                    .scale(s * mesh.fit, s * mesh.fit, s * mesh.fit)
                    .rotateX((float) Math.toRadians(ROT_X))
                    .rotateY((float) Math.toRadians(ROT_Y))
                    .translate(-mesh.centerX, -mesh.centerY, -mesh.centerZ);
            this.proj.mul(this.model, this.mvp);
        }
        ItemIconLighting.apply3D(this.shader);
        this.shader.setUniformMatrix4f("u_MVP", this.mvp);
        this.shader.setUniformMatrix4f("u_Model", this.model);
        /* Tiefentest pro Icon: durchdringende Modellteile (Zaun-Balken in den Pfosten) brauchen
           echte Verdeckung. Clear pro Icon -> das zuletzt gezeichnete Cursor-Icon bleibt oben.
           "or-equal"-Func, damit koplanare Overlay-Quads (Grasblock-Seite) exakt gewinnen. */
        GL11.glEnable(GL11.GL_DEPTH_TEST);
        GL11.glClear(GL11.GL_DEPTH_BUFFER_BIT);
        /* Funcs statisch aus EngineProperties statt glGetInteger (synchroner Roundtrip pro Icon). */
        EngineProperties properties = SkyEngine.get().getWindow().getProperties();
        GL11.glDepthFunc(properties.orEqualDepthFunc());
        mesh.render();
        GL11.glDepthFunc(properties.baseDepthFunc());
        GL11.glDisable(GL11.GL_DEPTH_TEST);
    }

    /**
     * Minecraft-{@code display.gui} des Inventarmodells inklusive Parent-Vererbung. Fehlt der
     * Kontext, verwendet der Aufrufer weiterhin die bisherige Standard-Blockisometrie.
     */
    private static ModelLoader.Display guiDisplayFor(Item item) {
        if (!(item instanceof BlockItem bi)) return null;
        return ModelLoader.display(BlockStateModels.inventoryDisplayModel(bi.getBlock()), "gui");
    }

    /** Zeichnet ein flaches, kamerazugewandtes Icon (kein Iso-Würfel) zentriert im Slot. */
    private void drawFlat(FlatIcon flat, float centerX, float centerY, float slotPixelSize, float vH) {
        float h = slotPixelSize;
        float w = flat.door ? slotPixelSize * 0.5f : slotPixelSize; // Tür schmal/hoch, Scheibe quadratisch
        float cyUp = vH - centerY;
        this.model.identity().translate(centerX - w / 2f, cyUp - h / 2f, 0).scale(w, h, 1f);
        this.proj.mul(this.model, this.mvp);
        ItemIconLighting.applyFlat(this.shader);
        this.shader.setUniformMatrix4f("u_MVP", this.mvp);
        this.shader.setUniformMatrix4f("u_Model", this.model);
        flat.mesh.render();
    }

    /**
     * Backt ein flaches Icon aus {@code icon_flat} (Liste von Texturpfaden, von unten nach oben
     * gestapelt). Ein Eintrag = ein Voll-Slot-Quad (Glasscheibe), zwei = Mini-Tür (Unter-/Oberhälfte).
     * Gibt {@link #NOT_FLAT} (mesh=null) zurück, wenn der Block kein flaches Icon definiert.
     */
    private FlatIcon bakeFlat(Item item) {
        String[] paths;
        int tint = BakedQuad.WHITE;
        if (item instanceof BlockItem bi) {
            /* icon_item (einzelnes Item-Sprite, MC-Look) hat Vorrang vor icon_flat. Der
               Block-Tint wird mitgenommen, damit z.B. tall_grass/fern gruen erscheinen. */
            String single = BlockStateModels.iconItem(bi.getBlock());
            paths = single != null ? new String[]{single} : BlockStateModels.flatIcon(bi.getBlock());
            tint = bi.getBlock().getTint();
        } else if (item.getIconTexture() != null) {
            /* Nicht-Block-Item mit eigener Textur (z.B. Eimer) -> einfaches Voll-Slot-Quad. */
            paths = new String[]{item.getIconTexture()};
        } else {
            paths = null;
        }
        if (paths == null || paths.length == 0) return NOT_FLAT;
        float r = ((tint >> 16) & 0xFF) / 255F;
        float g = ((tint >> 8) & 0xFF) / 255F;
        float b = (tint & 0xFF) / 255F;
        int n = paths.length;
        float[] data = new float[n * 6 * 12];
        int p = 0;
        for (int i = 0; i < n; i++) {
            int layer = BlockTextures.layerOf(paths[i]);
            p = flatQuad(data, p, (float) i / n, (float) (i + 1) / n, layer, r, g, b);
        }
        return new FlatIcon(new Mesh(data), n >= 2);
    }

    /** Ein Voll-Breite-Quad (x 0..1) im y-Bereich [ya,yb], z=0, volle UV, Farbe = Tint. CCW von +Z. */
    private static int flatQuad(float[] d, int p, float ya, float yb, int layer, float r, float g, float b) {
        p = flatVert(d, p, 0, ya, 0, 1, layer, r, g, b);
        p = flatVert(d, p, 1, ya, 1, 1, layer, r, g, b);
        p = flatVert(d, p, 1, yb, 1, 0, layer, r, g, b);
        p = flatVert(d, p, 1, yb, 1, 0, layer, r, g, b);
        p = flatVert(d, p, 0, yb, 0, 0, layer, r, g, b);
        p = flatVert(d, p, 0, ya, 0, 1, layer, r, g, b);
        return p;
    }

    private static int flatVert(float[] d, int p, float x, float y, float u, float v, int layer, float r, float g, float b) {
        d[p++] = x; d[p++] = y; d[p++] = 0; d[p++] = u; d[p++] = v; d[p++] = layer;
        d[p++] = r; d[p++] = g; d[p++] = b;
        d[p++] = 0; d[p++] = 0; d[p++] = 1;
        return p;
    }

    /** Liefert den BlockEntity-Renderer mit Icon-Fähigkeit für dieses Item, oder null. */
    private BlockEntityRenderer customIconFor(Item item) {
        if (this.blockEntityRenderers == null || !(item instanceof BlockItem bi)) return null;
        BlockEntityType<?> type = bi.getBlock().getBlockEntityType();
        if (type == null) return null;
        BlockEntityRenderer r = this.blockEntityRenderers.get(type);
        return (r != null && r.hasIcon()) ? r : null;
    }

    public void end() {
        this.shader.unbind();
        GL11.glDisable(GL11.GL_BLEND);
        GL11.glEnable(GL11.GL_DEPTH_TEST);
    }

    /** Backt die Quads als [x,y,z,u,v,layer,r,g,b,nx,ny,nz]. */
    private Mesh bake(Item item) {
        if (!(item instanceof BlockItem bi)) return null;
        /* bakeInventory nutzt ein optionales icon-spezifisches Modell (z.B. Zaun mit Armen, kleine
           Tür, flache Glasscheibe) statt des Default-State-Modells — sonst Fallback auf Default.
           Der Icon-Pfad backt frisch aus den Modell-JSONs und läuft damit am Retint in
           Block.bakeModel vorbei — Tint + Seiten-Overlay (Gras/Laub) hier explizit anwenden. */
        BakedQuad[] quads = bi.getBlock().applyTint(BlockStateModels.bakeInventory(bi.getBlock()).quads());
        BakedQuad[] overlay = bi.getBlock().getDefaultState().getOverlay();
        if (overlay.length > 0) quads = BlockModels.concat(quads, overlay);

        /* Blöcke mit leerem statischem Modell (z.B. die BER-gerenderte Truhe) hätten kein Icon —
           Fallback auf einen echten Würfel-Block (Eichenbretter), dessen Texturlayer garantiert im
           TextureArray liegt. Platzhalter, bis es ein dediziertes Item-/Chest-Modell gibt. */
        if (quads.length == 0) {
            BlockState fallback = Blocks.getState(Blocks.OAK_PLANKS);
            quads = BlockStateModels.bake(fallback.getBlock(), fallback).quads();
        }

        int verts = 0;
        for (BakedQuad q : quads) verts += q.vertices().length / 5;
        if (verts == 0) return null;

        float[] data = new float[verts * 12];
        int p = 0;
        for (BakedQuad q : quads) {
            float[] v = q.vertices();
            int n = v.length / 5;
            float[] normal = normalFor(v);
            int tint = q.tint();
            float r = ((tint >> 16) & 0xFF) / 255F;
            float g = ((tint >> 8) & 0xFF) / 255F;
            float b = (tint & 0xFF) / 255F;
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
                data[p++] = normal[0];
                data[p++] = normal[1];
                data[p++] = normal[2];
            }
        }
        float[] bounds = compositeBounds(quads);
        return new Mesh(data, bounds[0], bounds[1], bounds[2], bounds[3]);
    }

    /** Zentriert und skaliert nur Modelle, die mehr als eine Blockzelle belegen. */
    private static float[] compositeBounds(BakedQuad[] quads) {
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, minZ = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;
        for (BakedQuad quad : quads) {
            float[] vertices = quad.vertices();
            for (int i = 0; i < vertices.length; i += 5) {
                minX = Math.min(minX, vertices[i]);       maxX = Math.max(maxX, vertices[i]);
                minY = Math.min(minY, vertices[i + 1]);   maxY = Math.max(maxY, vertices[i + 1]);
                minZ = Math.min(minZ, vertices[i + 2]);   maxZ = Math.max(maxZ, vertices[i + 2]);
            }
        }
        float span = Math.max(maxX - minX, Math.max(maxY - minY, maxZ - minZ));
        if (span <= 1.001F) return new float[]{0.5F, 0.5F, 0.5F, 1F};
        return new float[]{(minX + maxX) * 0.5F, (minY + maxY) * 0.5F,
                (minZ + maxZ) * 0.5F, 1F / span};
    }

    /**
     * Berechnet die geometrische Normale aus dem ersten Dreieck der Quad-Vertices. Sie wird erst im
     * Shader durch den individuellen display.gui-Transform gedreht und dort wie in 26.2 beleuchtet.
     */
    private static float[] normalFor(float[] v) {
        float e1x = v[5] - v[0], e1y = v[6] - v[1], e1z = v[7] - v[2];
        float e2x = v[10] - v[0], e2y = v[11] - v[1], e2z = v[12] - v[2];
        float nx = e1y * e2z - e1z * e2y;
        float ny = e1z * e2x - e1x * e2z;
        float nz = e1x * e2y - e1y * e2x;
        float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (len == 0f) return new float[]{0F, 1F, 0F};
        nx /= len; ny /= len; nz /= len;
        return new float[]{nx, ny, nz};
    }

    public void dispose() {
        for (Mesh m : this.cache.values()) if (m != null) m.dispose();
        this.cache.clear();
        for (FlatIcon f : this.flatCache.values()) if (f.mesh != null) f.mesh.dispose();
        this.flatCache.clear();
        if (this.shader != null) this.shader.dispose();
    }

    private static final class Mesh {
        final int vao, vbo, count;
        final float centerX, centerY, centerZ, fit;

        Mesh(float[] data) {
            this(data, 0.5F, 0.5F, 0.5F, 1F);
        }

        Mesh(float[] data, float centerX, float centerY, float centerZ, float fit) {
            this.count = data.length / 12;
            this.centerX = centerX;
            this.centerY = centerY;
            this.centerZ = centerZ;
            this.fit = fit;
            this.vao = GL30.glGenVertexArrays();
            this.vbo = GL15.glGenBuffers();
            GL30.glBindVertexArray(this.vao);
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, this.vbo);
            GL15.glBufferData(GL15.GL_ARRAY_BUFFER, data, GL15.GL_STATIC_DRAW);
            GlDebug.labelBuffer(this.vbo, "ItemIconRenderer Mesh-VBO");
            int stride = 12 * Float.BYTES;
            GL20.glVertexAttribPointer(0, 3, GL11.GL_FLOAT, false, stride, 0);
            GL20.glVertexAttribPointer(1, 3, GL11.GL_FLOAT, false, stride, 3 * Float.BYTES);
            GL20.glVertexAttribPointer(2, 3, GL11.GL_FLOAT, false, stride, 6 * Float.BYTES);
            GL20.glVertexAttribPointer(3, 3, GL11.GL_FLOAT, false, stride, 9 * Float.BYTES);
            GL20.glEnableVertexAttribArray(0);
            GL20.glEnableVertexAttribArray(1);
            GL20.glEnableVertexAttribArray(2);
            GL20.glEnableVertexAttribArray(3);
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

    /** Flaches 2D-Icon. {@code mesh==null} markiert „nicht flach" (zwischengespeicherter Negativtreffer). */
    private static final class FlatIcon {
        final Mesh mesh;
        final boolean door;
        FlatIcon(Mesh mesh, boolean door) { this.mesh = mesh; this.door = door; }
    }

    private static final FlatIcon NOT_FLAT = new FlatIcon(null, false);

    private static final String VERTEX = """
        #version 460 core
        layout(location = 0) in vec3 a_position;
        layout(location = 1) in vec3 a_texCoord;
        layout(location = 2) in vec3 a_color;
        layout(location = 3) in vec3 a_normal;
        uniform mat4 u_MVP;
        uniform mat4 u_Model;
        out vec3 v_texCoord;
        out vec3 v_color;
        out vec3 v_normal;
        void main() {
            v_texCoord = a_texCoord;
            v_color = a_color;
            v_normal = normalize(transpose(inverse(mat3(u_Model))) * a_normal);
            gl_Position = u_MVP * vec4(a_position, 1.0);
        }
        """;

    private static final String FRAGMENT = """
        #version 460 core
        in vec3 v_texCoord;
        in vec3 v_color;
        in vec3 v_normal;
        uniform sampler2DArray u_Textures;
        uniform vec3 u_ItemLight0;
        uniform vec3 u_ItemLight1;
        out vec4 fragColor;
        void main() {
            vec4 c = texture(u_Textures, v_texCoord);
            if (c.a < 0.01) discard;
            vec3 n = normalize(v_normal);
            float diffuse = max(dot(u_ItemLight0, n), 0.0) + max(dot(u_ItemLight1, n), 0.0);
            float light = min(1.0, diffuse * 0.6 + 0.4);
            fragColor = vec4(c.rgb * v_color * light, c.a);
        }
        """;
}
