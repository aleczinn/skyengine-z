package de.skyengine.graphics.player;

import de.skyengine.core.file.FileHandle;
import de.skyengine.core.file.FileType;
import de.skyengine.game.world.block.BlockTextures;
import de.skyengine.game.world.block.Blocks;
import de.skyengine.game.world.block.model.BakedQuad;
import de.skyengine.game.world.block.model.BlockModels;
import de.skyengine.game.world.block.model.BlockStateModels;
import de.skyengine.game.world.block.model.ModelLoader;
import de.skyengine.game.world.block.state.BlockState;
import de.skyengine.game.world.item.BlockItem;
import de.skyengine.game.world.item.Item;
import de.skyengine.game.world.item.ToolItem;
import de.skyengine.game.world.block.entity.BlockEntityType;
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
    /** Für BER-Blöcke ohne statisches Modell (Truhe): eigener Renderer statt Planks-Fallback. */
    private BlockEntityRenderDispatcher blockEntityRenderers;

    /** model = Modellname für die Display-Sektion ({@code block/<id>}); null bei flachen Items. */
    private record HeldMesh(Mesh mesh, boolean flat, boolean handheld, BlockEntityRenderer custom, String model) {}
    private static final HeldMesh EMPTY = new HeldMesh(null, false, false, null, null);

    private final Map<Item, HeldMesh> cache = new HashMap<>();
    private final Matrix4f transform = new Matrix4f();
    private final Matrix4f projView = new Matrix4f();
    private final Matrix4f mvp = new Matrix4f();
    private boolean cullWasEnabled;
    /* Licht des laufenden bind()-Abschnitts — der BER-Sonderweg (Truhe) zeichnet mit seinem
       EIGENEN Shader und braucht den Wert deshalb als Parameter statt als Uniform. */
    private float heldLight = 1.0F;

    public void init(TextureArray textures, BlockEntityRenderDispatcher blockEntityRenderers) {
        this.textures = textures;
        this.blockEntityRenderers = blockEntityRenderers;
        this.shader = new ShaderProgram(
                new Shader(VERTEX, ShaderType.VERTEX),
                new Shader(FRAGMENT, ShaderType.FRAGMENT));
    }

    /**
     * Bindet Shader + TextureArray und schaltet Culling aus (Restore in {@link #unbind}).
     *
     * @param light Himmelslicht-Faktor der Zelle ({@code ChunkRenderer.skyLightFactor}); in der
     *              Inventar-Vorschau <b>1.0</b>, sonst dunkelt die GUI mit der Welt ab. Gilt auch
     *              für den BER-Sonderweg (Truhe in der Hand), s. {@link #heldLight}.
     */
    public void bind(Matrix4f projectionView, float light) {
        this.cullWasEnabled = GL11.glIsEnabled(GL11.GL_CULL_FACE);
        GL11.glDisable(GL11.GL_CULL_FACE);
        this.projView.set(projectionView);
        this.heldLight = light;
        this.shader.bind();
        this.shader.setUniformMatrix4f("u_ProjectionView", projectionView);
        this.shader.setUniformi("u_Textures", 0);
        this.shader.setUniformf("u_Light", light);
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
        if (held.custom != null) {
            /* BER-Block (Truhe): gleiche Display-Kette wie der Block-Zweig, aber der eigene
               Renderer zeichnet (eigener Shader/Textur) — danach unseren State wiederherstellen. */
            this.transform.set(base)
                    .rotateXYZ(0F, (float) Math.toRadians(45), 0F).scale(0.40F)
                    .translate(-0.5F, -0.5F, -0.5F);
            this.projView.mul(this.transform, this.mvp);
            held.custom.renderHeld(this.mvp, this.heldLight);
            this.restoreAfterCustom();
            return;
        }
        if (held.mesh == null) return;
        this.transform.set(base);
        if (held.flat) {
            this.transform.translate(1.13F / 16F, 3.2F / 16F, 1.13F / 16F)
                    .rotateXYZ(0F, (float) Math.toRadians(-90), (float) Math.toRadians(25))
                    .scale(0.68F);
        } else if (!this.applyDisplay(held.model, "firstperson_righthand", 1F / 16F, 1F)) {
            this.transform.rotateXYZ(0F, (float) Math.toRadians(45), 0F).scale(0.40F);
        }
        this.transform.translate(-0.5F, -0.5F, -0.5F);
        this.shader.setUniformMatrix4f("u_Model", this.transform);
        if (!held.flat) GL11.glEnable(GL11.GL_CULL_FACE);   // Block-Würfel: Rückseiten cullen (Glas wie Vanilla)
        held.mesh.render();
        if (!held.flat) GL11.glDisable(GL11.GL_CULL_FACE);
    }

    /**
     * Third-Person/Vorschau: {@code base} = Anker-Matrix in Modell-PX (nach ItemInHandLayer).
     * Display-Translationen sind px-Werte aus den Vanilla-JSONs, Scale aufs 0..1-Mesh ×16.
     */
    public void drawThirdPerson(Item item, Matrix4f base) {
        HeldMesh held = this.meshFor(item);
        if (held.custom != null) {
            /* BER-Block (Truhe): Block-Display-Kette, gezeichnet vom eigenen Renderer. */
            this.transform.set(base)
                    .translate(0F, 2.5F, 0F)
                    .rotateXYZ((float) Math.toRadians(75), (float) Math.toRadians(45), 0F)
                    .scale(16F * 0.375F)
                    .translate(-0.5F, -0.5F, -0.5F);
            this.projView.mul(this.transform, this.mvp);
            held.custom.renderHeld(this.mvp, this.heldLight);
            this.restoreAfterCustom();
            return;
        }
        if (held.mesh == null) return;
        this.transform.set(base);
        if (!held.flat) {
            if (!this.applyDisplay(held.model, "thirdperson_righthand", 1F, 16F)) {
                this.transform.translate(0F, 2.5F, 0F)
                        .rotateXYZ((float) Math.toRadians(75), (float) Math.toRadians(45), 0F)
                        .scale(16F * 0.375F);
            }
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
        if (!held.flat) GL11.glEnable(GL11.GL_CULL_FACE);   // Block-Würfel: Rückseiten cullen (Glas wie Vanilla)
        held.mesh.render();
        if (!held.flat) GL11.glDisable(GL11.GL_CULL_FACE);
    }

    private HeldMesh meshFor(Item item) {
        return this.cache.computeIfAbsent(item, this::bake);
    }

    /**
     * Wendet die {@code display}-Sektion des Modells an (MC-Reihenfolge: translate, rotate,
     * scale). {@code false}, wenn das Modell für diesen Kontext nichts liefert — dann bleibt
     * der hartkodierte Vanilla-Default des Aufrufers stehen.
     *
     * @param translationUnit 1/16 wenn {@code base} in Blockeinheiten rechnet (First-Person),
     *                        1 wenn in Modell-Pixeln (Third-Person)
     * @param scaleUnit       Gegenstück dazu: 1 bzw. 16, weil das Mesh selbst 0..1 groß ist
     */
    private boolean applyDisplay(String model, String slot, float translationUnit, float scaleUnit) {
        if (model == null) return false;
        ModelLoader.Display d = ModelLoader.display(model, slot);
        if (d == null) return false;
        this.transform
                .translate(d.translation()[0] * translationUnit,
                           d.translation()[1] * translationUnit,
                           d.translation()[2] * translationUnit)
                .rotateXYZ((float) Math.toRadians(d.rotation()[0]),
                           (float) Math.toRadians(d.rotation()[1]),
                           (float) Math.toRadians(d.rotation()[2]))
                .scale(d.scale()[0] * scaleUnit, d.scale()[1] * scaleUnit, d.scale()[2] * scaleUnit);
        return true;
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
            return new HeldMesh(buildExtruded(paths[0], tint), true, handheld, null, null);
        }
        if (paths != null && paths.length > 1) {
            return new HeldMesh(buildFlat(paths, tint), true, handheld, null, null);
        }
        if (item instanceof BlockItem bi) {
            /* BER-Block ohne statisches Modell (Truhe): eigener Renderer statt Planks-Fallback.
               Greift NUR bei leerem Modell — Blöcke mit echtem Modell (Zaubertisch) unberührt. */
            BakedQuad[] quads = bi.getBlock().getDefaultState().getModel();
            if (quads == null || quads.length == 0) {
                BlockEntityRenderer custom = this.customHeldFor(bi);
                if (custom != null) return new HeldMesh(null, false, false, custom, null);
            }
            /* Deklariert der Block ein inventory_model (Zaun mit Armen, Glasscheibe), gilt es auch
               in der Hand — sonst hielte man beim Zaun nur den nackten Pfosten. Dieser Pfad backt
               frisch aus den Modell-JSONs und läuft damit am Retint in Block.bakeModel vorbei,
               der Tint muss also explizit angewandt werden (wie im ItemIconRenderer). */
            ModelLoader.Baked inventory = BlockStateModels.inventoryOverride(bi.getBlock());
            Mesh mesh = inventory != null
                    ? buildBlock(bi.getBlock().applyTint(inventory.quads()))
                    : buildBlock(bi.getBlock().getDefaultState());
            /* Modellname für die display-Sektion; block/block liefert den Vanilla-Default. */
            String model = "block/" + bi.getBlock().getIdentifier().path();
            if (mesh != null) return new HeldMesh(mesh, false, false, null, model);
        }
        return EMPTY;
    }

    /** BlockEntity-Renderer mit eigenem Modell (Vorbild ItemIconRenderer.customIconFor). */
    private BlockEntityRenderer customHeldFor(BlockItem bi) {
        if (this.blockEntityRenderers == null) return null;
        BlockEntityType<?> type = bi.getBlock().getBlockEntityType();
        if (type == null) return null;
        BlockEntityRenderer r = this.blockEntityRenderers.get(type);
        return (r != null && r.hasIcon()) ? r : null;
    }

    /** Nach einem BER-Draw eigenen Shader/Textur wiederherstellen (u_ProjectionView persistiert). */
    private void restoreAfterCustom() {
        this.shader.bind();
        this.shader.setUniformi("u_Textures", 0);
        this.shader.setUniformf("u_Light", this.heldLight);
        this.textures.bind(0);
    }

    /* --- Sprite-Extrusion (MC ItemModelGenerator): 0..1 in x/y, 1 px dick um z=0.5 --- */

    private static final float Z_BACK = 0.5F - 0.5F / 16F;
    private static final float Z_FRONT = 0.5F + 0.5F / 16F;

    /* Gerichtetes Face-Shading für extrudierte Item-Sprites. BEWUSST abweichend von
       BlockModels.FACE_BRIGHTNESS: In der First-Person-Pose (Display-Rotation [0,-90,25])
       dominiert die große Vorder-/Rückseite den Blick, während die dünne Oberseite kaum
       sichtbar ist. Damit das Item wie in Minecraft „von oben beleuchtet" wirkt, ist die
       große flache Fläche die DUNKELSTE große Fläche, die extrudierten Seitenwände sind
       heller, die Oberseite am hellsten. */
    private static final float ITEM_FACE_FRONT = 0.6F;   // große Vorder-/Rückseite (dunkelste Fläche)
    private static final float ITEM_FACE_SIDE = 0.8F;    // linke/rechte Seitenwand
    private static final float ITEM_FACE_TOP = 1.0F;     // obere Wand (am hellsten)
    private static final float ITEM_FACE_BOTTOM = 0.5F;  // untere Wand (am dunkelsten)

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

        /* Pose-angepasstes Face-Shading (siehe ITEM_FACE_*). */
        float frB = ITEM_FACE_FRONT, baB = ITEM_FACE_FRONT;      // Vorder-/Rückseite
        float wB = ITEM_FACE_SIDE, eB = ITEM_FACE_SIDE;          // linke/rechte Wand
        float tB = ITEM_FACE_TOP, bB = ITEM_FACE_BOTTOM;         // obere/untere Wand

        float[] data = new float[(2 + walls) * 6 * FLOATS_PER_VERTEX];
        int p = 0;
        /* Vorderseite (volle UV; Transparenz macht der discard) + gespiegelte Rückseite.
           Mesh-y 0 = unten = Textur-v 1 (Pixelzeile h-1). */
        p = quad(data, p,
                0, 0, Z_FRONT, 1, 0, Z_FRONT, 1, 1, Z_FRONT, 0, 1, Z_FRONT,
                0, 1, 1, 1, 1, 0, 0, 0, layer, r * frB, g * frB, b * frB);
        p = quad(data, p,
                1, 0, Z_BACK, 0, 0, Z_BACK, 0, 1, Z_BACK, 1, 1, Z_BACK,
                1, 1, 0, 1, 0, 0, 1, 0, layer, r * baB, g * baB, b * baB);

        for (int py = 0; py < h; py++) {
            for (int px = 0; px < w; px++) {
                if (!opaque(pixels, w, h, px, py)) continue;
                float x0 = px / (float) w, x1 = (px + 1) / (float) w;
                float yT = 1F - py / (float) h, yB = 1F - (py + 1) / (float) h;
                float u = (px + 0.5F) / w, v = (py + 0.5F) / h;   // Kanten-Farbe = Texel-Zentrum
                if (!opaque(pixels, w, h, px - 1, py)) {          // linke Wand (west)
                    p = wall(data, p, x0, yB, Z_BACK, x0, yB, Z_FRONT, x0, yT, Z_FRONT, x0, yT, Z_BACK, u, v, layer, r * wB, g * wB, b * wB);
                }
                if (!opaque(pixels, w, h, px + 1, py)) {          // rechte Wand (ost)
                    p = wall(data, p, x1, yB, Z_FRONT, x1, yB, Z_BACK, x1, yT, Z_BACK, x1, yT, Z_FRONT, u, v, layer, r * eB, g * eB, b * eB);
                }
                if (!opaque(pixels, w, h, px, py - 1)) {          // obere Wand (oben)
                    p = wall(data, p, x0, yT, Z_FRONT, x1, yT, Z_FRONT, x1, yT, Z_BACK, x0, yT, Z_BACK, u, v, layer, r * tB, g * tB, b * tB);
                }
                if (!opaque(pixels, w, h, px, py + 1)) {          // untere Wand (unten)
                    p = wall(data, p, x0, yB, Z_BACK, x1, yB, Z_BACK, x1, yB, Z_FRONT, x0, yB, Z_FRONT, u, v, layer, r * bB, g * bB, b * bB);
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
        /* Vorder-/Rückseite konsistent mit dem Extrusions-Pfad dimmen. */
        float frB = ITEM_FACE_FRONT, baB = ITEM_FACE_FRONT;
        float[] data = new float[n * 12 * FLOATS_PER_VERTEX];
        int p = 0;
        for (int i = 0; i < n; i++) {
            int layer = BlockTextures.layerOf(paths[i]);
            float ya = (float) i / n, yb = (float) (i + 1) / n;
            p = quad(data, p,
                    0, ya, 0.5F, 1, ya, 0.5F, 1, yb, 0.5F, 0, yb, 0.5F,
                    0, 1, 1, 1, 1, 0, 0, 0, layer, r * frB, g * frB, b * frB);
            p = quad(data, p,
                    1, ya, 0.5F, 0, ya, 0.5F, 0, yb, 0.5F, 1, yb, 0.5F,
                    1, 1, 0, 1, 0, 0, 1, 0, layer, r * baB, g * baB, b * baB);
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
        return buildBlock(quads);
    }

    private static Mesh buildBlock(BakedQuad[] quads) {
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
        /* Himmelslicht der Zelle, fertig durch die Kurve gerechnet
           (ChunkRenderer.skyLightFactor). 1.0 = voll hell, Fullbright ODER GUI-Vorschau. */
        uniform float u_Light;
        out vec4 fragColor;
        void main() {
            vec4 c = texture(u_Textures, v_texCoord);
            if (c.a < 0.5) discard;
            fragColor = vec4(c.rgb * v_color * u_Light, c.a);
        }
        """;
}
