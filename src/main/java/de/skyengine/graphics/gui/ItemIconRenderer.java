package de.skyengine.graphics.gui;

import de.skyengine.game.world.block.BlockTextures;
import de.skyengine.game.world.block.Blocks;
import de.skyengine.game.world.block.entity.BlockEntityType;
import de.skyengine.game.world.block.model.BakedQuad;
import de.skyengine.game.world.block.model.BlockStateModels;
import de.skyengine.game.world.block.state.BlockState;
import de.skyengine.game.world.item.BlockItem;
import de.skyengine.game.world.item.Item;
import de.skyengine.game.world.item.ItemStack;
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
 * <p>Kein Tiefentest nötig: Back-Face-Culling (global an) lässt nur die drei sichtbaren Würfelseiten
 * übrig, die sich in der Projektion nicht überlappen. Reihenfolge der Icons ist daher egal; das
 * Cursor-Icon wird zuletzt gezeichnet und liegt dadurch oben.
 */
public final class ItemIconRenderer {

    /* Isometrische Ausrichtung. ROT_X=30 wie MC; ROT_Y=135 statt 225, damit das Richtungs-Shading
       (hellere 0.8-Seite vs dunklere 0.6-Seite) wie in Minecraft RECHTS heller ist (225 zeigte es
       links). Eine 90°-Drehung vertauscht die beiden sichtbaren Seitenflächen. Richtungsblöcke
       (Truhe/Treppe) kompensieren das über ihre eigene Rotation, sodass ihre Netto-Drehung gleich
       bleibt (Truhe: 270° Vorrotation, Treppe: inventory_y=270 -> netto 45° wie zuvor). */
    private static final float ROT_X = 30f;
    private static final float ROT_Y = 135f;
    private static final float ICON_SCALE = 0.66f; // Würfelkante als Anteil der Slot-Pixelgröße

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
        this.proj.identity().ortho(0, vW, 0, vH, -2000, 2000, true);
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
        this.model.identity()
                .translate(centerX, cyUp, 0)
                .scale(s, s, s)
                .rotateX((float) Math.toRadians(ROT_X))
                .rotateY((float) Math.toRadians(ROT_Y))
                .translate(-0.5f, -0.5f, -0.5f);
        this.proj.mul(this.model, this.mvp);

        /* Block-Entity-Icon (z.B. Truhe): echtes BER-Modell + dessen 2D-Textur statt Block-Quads. */
        BlockEntityRenderer custom = customIconFor(stack.getItem());
        if (custom != null) {
            custom.renderIcon(this.mvp);
            /* Shader/Textur des Würfel-Pfads für nachfolgende Icons wiederherstellen. */
            this.shader.bind();
            this.shader.setUniformi("u_Textures", 0);
            this.textures.bind(0);
            return;
        }

        Mesh mesh = this.cache.computeIfAbsent(stack.getItem(), this::bake);
        if (mesh == null || mesh.count == 0) return;
        this.shader.setUniformMatrix4f("u_MVP", this.mvp);
        mesh.render();
    }

    /** Zeichnet ein flaches, kamerazugewandtes Icon (kein Iso-Würfel) zentriert im Slot. */
    private void drawFlat(FlatIcon flat, float centerX, float centerY, float slotPixelSize, float vH) {
        float h = slotPixelSize;
        float w = flat.door ? slotPixelSize * 0.5f : slotPixelSize; // Tür schmal/hoch, Scheibe quadratisch
        float cyUp = vH - centerY;
        this.model.identity().translate(centerX - w / 2f, cyUp - h / 2f, 0).scale(w, h, 1f);
        this.proj.mul(this.model, this.mvp);
        this.shader.setUniformMatrix4f("u_MVP", this.mvp);
        flat.mesh.render();
    }

    /**
     * Backt ein flaches Icon aus {@code icon_flat} (Liste von Texturpfaden, von unten nach oben
     * gestapelt). Ein Eintrag = ein Voll-Slot-Quad (Glasscheibe), zwei = Mini-Tür (Unter-/Oberhälfte).
     * Gibt {@link #NOT_FLAT} (mesh=null) zurück, wenn der Block kein flaches Icon definiert.
     */
    private FlatIcon bakeFlat(Item item) {
        if (!(item instanceof BlockItem bi)) return NOT_FLAT;
        String[] paths = BlockStateModels.flatIcon(bi.getBlock());
        if (paths == null || paths.length == 0) return NOT_FLAT;
        int n = paths.length;
        float[] data = new float[n * 6 * 7];
        int p = 0;
        for (int i = 0; i < n; i++) {
            int layer = BlockTextures.layerOf(paths[i]);
            p = flatQuad(data, p, (float) i / n, (float) (i + 1) / n, layer);
        }
        return new FlatIcon(new Mesh(data), n >= 2);
    }

    /** Ein Voll-Breite-Quad (x 0..1) im y-Bereich [ya,yb], z=0, volle UV, voll hell. CCW von +Z. */
    private static int flatQuad(float[] d, int p, float ya, float yb, int layer) {
        p = flatVert(d, p, 0, ya, 0, 1, layer);
        p = flatVert(d, p, 1, ya, 1, 1, layer);
        p = flatVert(d, p, 1, yb, 1, 0, layer);
        p = flatVert(d, p, 1, yb, 1, 0, layer);
        p = flatVert(d, p, 0, yb, 0, 0, layer);
        p = flatVert(d, p, 0, ya, 0, 1, layer);
        return p;
    }

    private static int flatVert(float[] d, int p, float x, float y, float u, float v, int layer) {
        d[p++] = x; d[p++] = y; d[p++] = 0; d[p++] = u; d[p++] = v; d[p++] = layer; d[p++] = 1f;
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

    /** Backt die Quads des Block-Default-States in ein interleaved Mesh [x,y,z,u,v,layer,brightness]. */
    private Mesh bake(Item item) {
        if (!(item instanceof BlockItem bi)) return null;
        /* bakeInventory nutzt ein optionales icon-spezifisches Modell (z.B. Zaun mit Armen, kleine
           Tür, flache Glasscheibe) statt des Default-State-Modells — sonst Fallback auf Default. */
        BakedQuad[] quads = BlockStateModels.bakeInventory(bi.getBlock()).quads();

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

        float[] data = new float[verts * 7];
        int p = 0;
        for (BakedQuad q : quads) {
            float[] v = q.vertices();
            int n = v.length / 5;
            for (int i = 0; i < n; i++) {
                data[p++] = v[i * 5];
                data[p++] = v[i * 5 + 1];
                data[p++] = v[i * 5 + 2];
                data[p++] = v[i * 5 + 3];
                data[p++] = v[i * 5 + 4];
                data[p++] = q.textureLayer();
                data[p++] = q.brightness();
            }
        }
        return new Mesh(data);
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

        Mesh(float[] data) {
            this.count = data.length / 7;
            this.vao = GL30.glGenVertexArrays();
            this.vbo = GL15.glGenBuffers();
            GL30.glBindVertexArray(this.vao);
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, this.vbo);
            GL15.glBufferData(GL15.GL_ARRAY_BUFFER, data, GL15.GL_STATIC_DRAW);
            int stride = 7 * Float.BYTES;
            GL20.glVertexAttribPointer(0, 3, GL11.GL_FLOAT, false, stride, 0);
            GL20.glVertexAttribPointer(1, 3, GL11.GL_FLOAT, false, stride, 3 * Float.BYTES);
            GL20.glVertexAttribPointer(2, 1, GL11.GL_FLOAT, false, stride, 6 * Float.BYTES);
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
        layout(location = 2) in float a_brightness;
        uniform mat4 u_MVP;
        out vec3 v_texCoord;
        out float v_brightness;
        void main() {
            v_texCoord = a_texCoord;
            v_brightness = a_brightness;
            gl_Position = u_MVP * vec4(a_position, 1.0);
        }
        """;

    private static final String FRAGMENT = """
        #version 460 core
        in vec3 v_texCoord;
        in float v_brightness;
        uniform sampler2DArray u_Textures;
        out vec4 fragColor;
        void main() {
            vec4 c = texture(u_Textures, v_texCoord);
            if (c.a < 0.01) discard;
            fragColor = vec4(c.rgb * v_brightness, c.a);
        }
        """;
}
