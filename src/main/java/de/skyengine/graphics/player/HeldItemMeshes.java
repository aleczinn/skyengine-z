package de.skyengine.graphics.player;

import de.skyengine.game.world.block.Blocks;
import de.skyengine.game.world.block.model.BakedQuad;
import de.skyengine.game.world.block.model.BlockModels;
import de.skyengine.game.world.block.model.BlockStateModels;
import de.skyengine.game.world.block.model.ModelLoader;
import de.skyengine.game.world.block.RenderLayer;
import de.skyengine.game.world.block.state.BlockState;
import de.skyengine.game.world.item.BlockItem;
import de.skyengine.game.world.item.Item;
import de.skyengine.game.world.item.ToolItem;
import de.skyengine.game.world.block.entity.BlockEntityType;
import de.skyengine.graphics.GlDebug;
import de.skyengine.graphics.ItemSpriteBuilder;
import de.skyengine.graphics.GlState;
import de.skyengine.graphics.blockentity.BlockEntityRenderDispatcher;
import de.skyengine.graphics.blockentity.BlockEntityRenderer;
import de.skyengine.graphics.shader.Shader;
import de.skyengine.graphics.shader.ShaderProgram;
import de.skyengine.graphics.shader.ShaderType;
import de.skyengine.graphics.texture.TextureArray;
import de.skyengine.graphics.texture.BlockTextureAtlas;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

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
 * <p>Die Sprite-Geometrie kommt aus {@link de.skyengine.graphics.ItemSpriteBuilder} (geteilt mit
 * den gedroppten Items im {@code EntityRenderer}); hier stehen nur noch die pose-abhängigen
 * Face-Helligkeiten. Bake-Vorlage für die Block-Quads: {@code EntityRenderer.build}; Shader =
 * Kopie EntityRenderer (sampler2DArray). Culling wird in {@link #bind} deaktiviert
 * (Item-Windings gemischt, Items rotieren frei).
 */
public final class HeldItemMeshes {

    /** pos3 + texCoord3(u,v,layer) + rgb3 — muss zu {@link ItemSpriteBuilder#FLOATS_PER_VERTEX} passen. */
    private static final int FLOATS_PER_VERTEX = ItemSpriteBuilder.FLOATS_PER_VERTEX;

    private ShaderProgram shader;
    private TextureArray textures;
    /** Für BER-Blöcke ohne statisches Modell (Truhe): eigener Renderer statt Planks-Fallback. */
    private BlockEntityRenderDispatcher blockEntityRenderers;

    /**
     * model = Modellname für die Display-Sektion ({@code block/<id>}); null bei flachen Items.
     * translucent = der Block liegt im TRANSLUCENT-Layer (Slime, Honig, Eis, Glas) und braucht
     * beim Zeichnen Blending statt des harten Cutout-Tests — flache Item-Sprites nie.
     */
    private record HeldMesh(Mesh mesh, boolean flat, boolean handheld, BlockEntityRenderer custom,
                            String model, boolean translucent, float pivotX, float pivotY, float pivotZ) {}
    private static final HeldMesh EMPTY = new HeldMesh(null, false, false, null, null, false,
            0.5F, 0.5F, 0.5F);

    private final Map<Item, HeldMesh> cache = new HashMap<>();
    private final Matrix4f transform = new Matrix4f();
    private final Matrix4f projView = new Matrix4f();
    private final Matrix4f mvp = new Matrix4f();
    private boolean cullWasEnabled;
    private boolean blendWasEnabled;
    /** Alpha-Test-Schwellen wie im ChunkRenderer: harter Cutout bzw. praktisch aus fürs Blending. */
    private static final float CUTOUT_ALPHA = 0.5F;
    private static final float TRANSLUCENT_ALPHA = 0.001F;
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
     * @param light Licht-Faktor der Zelle (Himmel + Block) ({@code ChunkRenderer.lightFactor}); in der
     *              Inventar-Vorschau <b>1.0</b>, sonst dunkelt die GUI mit der Welt ab. Gilt auch
     *              für den BER-Sonderweg (Truhe in der Hand), s. {@link #heldLight}.
     */
    public void bind(Matrix4f projectionView, float light) {
        this.cullWasEnabled = GlState.isCullFaceEnabled();
        /* Blend-Zustand des Aufrufers merken statt ihn am Ende hart abzuschalten: die
           Inventar-Vorschau zeichnet mitten in der GUI, die Blending braucht. */
        this.blendWasEnabled = GL11.glIsEnabled(GL11.GL_BLEND);
        GlState.disableCullFace();
        this.projView.set(projectionView);
        this.heldLight = light;
        this.shader.bind();
        this.shader.setUniformMatrix4f("u_ProjectionView", projectionView);
        this.shader.setUniformi("u_Textures", 0);
        this.shader.setUniformi("u_NormalTextures", 1);
        this.shader.setUniformi("u_MaterialTextures", 2);
        this.shader.setUniformf("u_Light", light);
        this.shader.setUniformf("u_AlphaCutoff", CUTOUT_ALPHA);
        this.textures.bind(0);
        BlockTextureAtlas.bindOptionalMaterials(this.shader);
    }

    public void unbind() {
        this.shader.unbind();
        if (this.cullWasEnabled) GlState.enableCullFace();
        if (this.blendWasEnabled) {
            GL11.glEnable(GL11.GL_BLEND);
        } else {
            GL11.glDisable(GL11.GL_BLEND);
        }
    }

    /**
     * Zeichnet das Mesh mit dem Zustand, den sein RenderLayer braucht: transluzente Blöcke mit
     * Blending und praktisch ohne Alpha-Test, alles andere als harter Cutout — dieselbe
     * Aufteilung wie die drei Passes des {@code ChunkRenderer}. Ohne das wären Slime, Honig und
     * Eis in der Hand deckend, weil ihre Textur mit Alpha ≈ 0,7 am 0,5-Test vorbeikommt.
     */
    private void drawMesh(HeldMesh held) {
        if (held.translucent) {
            GL11.glEnable(GL11.GL_BLEND);
            this.shader.setUniformf("u_AlphaCutoff", TRANSLUCENT_ALPHA);
        }
        if (!held.flat) GlState.enableCullFace();   // Block-Würfel: Rückseiten cullen (Glas wie Vanilla)
        held.mesh.render();
        if (!held.flat) GlState.disableCullFace();
        if (held.translucent) {
            GL11.glDisable(GL11.GL_BLEND);
            this.shader.setUniformf("u_AlphaCutoff", CUTOUT_ALPHA);
        }
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
        this.transform.translate(-held.pivotX, -held.pivotY, -held.pivotZ);
        this.shader.setUniformMatrix4f("u_Model", this.transform);
        this.drawMesh(held);
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
        this.transform.translate(-held.pivotX, -held.pivotY, -held.pivotZ);
        this.shader.setUniformMatrix4f("u_Model", this.transform);
        this.drawMesh(held);
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
        /* Flache Sprites bleiben immer beim Cutout-Test: sie sind ausgestanzte Icons, kein
           transluzentes Material — ein Blend-Pfad wuerde dort nur weiche Kanten erzeugen. */
        if (paths != null && paths.length == 1) {
            return new HeldMesh(buildExtruded(paths[0], tint), true, handheld, null, null, false,
                    0.5F, 0.5F, 0.5F);
        }
        if (paths != null && paths.length > 1) {
            return new HeldMesh(buildFlat(paths, tint), true, handheld, null, null, false,
                    0.5F, 0.5F, 0.5F);
        }
        if (item instanceof BlockItem bi) {
            /* BER-Block ohne statisches Modell (Truhe): eigener Renderer statt Planks-Fallback.
               Greift NUR bei leerem Modell — Blöcke mit echtem Modell (Zaubertisch) unberührt. */
            BakedQuad[] quads = bi.getBlock().getDefaultState().getModel();
            if (quads == null || quads.length == 0) {
                BlockEntityRenderer custom = this.customHeldFor(bi);
                if (custom != null) return new HeldMesh(null, false, false, custom, null, false,
                        0.5F, 0.5F, 0.5F);
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
            String model = BlockStateModels.inventoryDisplayModel(bi.getBlock());
            boolean translucent =
                    bi.getBlock().getDefaultState().getRenderLayer() == RenderLayer.TRANSLUCENT;
            /* Minecraft rotiert jedes Block-Item um den festen Modellursprung (8/8/8 px), auch
               wenn mehrere Teilmodelle ueber die normale Blockzelle hinausragen. */
            if (mesh != null) return new HeldMesh(mesh, false, false, null, model, translucent,
                    0.5F, 0.5F, 0.5F);
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
        this.shader.setUniformf("u_AlphaCutoff", CUTOUT_ALPHA);
        this.textures.bind(0);
        BlockTextureAtlas.bindOptionalMaterials(this.shader);
    }

    /* --- Sprite-Extrusion: Geometrie kommt aus ItemSpriteBuilder, hier nur die Pose-Helligkeiten --- */

    /* Gerichtetes Face-Shading fuer extrudierte Item-Sprites. BEWUSST abweichend von
       BlockModels.FACE_BRIGHTNESS: In der First-Person-Pose (Display-Rotation [0,-90,25])
       dominiert die grosse Vorder-/Rueckseite den Blick, waehrend die duenne Oberseite kaum
       sichtbar ist. Damit das Item wie in Minecraft "von oben beleuchtet" wirkt, ist die
       grosse flache Flaeche die DUNKELSTE grosse Flaeche, die extrudierten Seitenwaende sind
       heller, die Oberseite am hellsten. */
    private static final float ITEM_FACE_FRONT = 0.6F;   // grosse Vorder-/Rueckseite (dunkelste Flaeche)
    private static final float ITEM_FACE_SIDE = 0.8F;    // linke/rechte Seitenwand
    private static final float ITEM_FACE_TOP = 1.0F;     // obere Wand (am hellsten)
    private static final float ITEM_FACE_BOTTOM = 0.5F;  // untere Wand (am dunkelsten)

    private static Mesh buildExtruded(String path, int tint) {
        return new Mesh(ItemSpriteBuilder.extrude(path, tint,
                ITEM_FACE_FRONT, ITEM_FACE_SIDE, ITEM_FACE_TOP, ITEM_FACE_BOTTOM));
    }

    /** Quad-Stapel x/y 0..1 bei z=0.5, jede Lage vorder- UND rueckseitig (Tuer = 2 Lagen). */
    private static Mesh buildFlat(String[] paths, int tint) {
        return new Mesh(ItemSpriteBuilder.flat(paths, tint, ITEM_FACE_FRONT));
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
        out vec3 v_pos;
        void main() {
            v_texCoord = a_texCoord;
            v_color = a_color;
            vec4 p = u_Model * vec4(a_position, 1.0);
            v_pos = p.xyz;
            gl_Position = u_ProjectionView * p;
        }
        """;

    private static final String FRAGMENT = """
        #version 460 core
        in vec3 v_texCoord;
        in vec3 v_color;
        in vec3 v_pos;
        uniform sampler2DArray u_Textures;
        uniform sampler2DArray u_NormalTextures;
        uniform sampler2DArray u_MaterialTextures;
        uniform int u_PbrEnabled;
        /* Licht der Zelle (Himmel + Block), fertig durch die Kurve gerechnet
           (ChunkRenderer.lightFactor). 1.0 = voll hell, Fullbright ODER GUI-Vorschau. */
        uniform float u_Light;
        /* Wie im ChunkRenderer: 0.5 = harter Cutout (Sprites, Laub, Fackel), 0.001 = praktisch
           aus, damit ein transluzenter Block sein echtes Alpha ins Blending bringt. */
        uniform float u_AlphaCutoff;
        out vec4 fragColor;
        vec3 materialLight(vec3 albedo) {
            if (u_PbrEnabled == 0) return albedo;
            vec4 ntex = texture(u_NormalTextures, v_texCoord);
            vec4 mat = texture(u_MaterialTextures, v_texCoord);
            if (ntex.a <= 0.0 && mat.a <= 0.0) return albedo;
            vec3 gn = normalize(cross(dFdx(v_pos), dFdy(v_pos)));
            if (!gl_FrontFacing) gn = -gn;
            vec3 dp1=dFdx(v_pos), dp2=dFdy(v_pos); vec2 du1=dFdx(v_texCoord.xy), du2=dFdy(v_texCoord.xy);
            vec3 t=cross(dp2,gn)*du1.x+cross(gn,dp1)*du2.x;
            vec3 b=cross(dp2,gn)*du1.y+cross(gn,dp1)*du2.y;
            float s=inversesqrt(max(max(dot(t,t),dot(b,b)),1e-8));
            vec3 n=ntex.a>0.0?normalize(mat3(t*s,b*s,gn)*(ntex.rgb*2.0-1.0)):gn;
            vec3 l=normalize(vec3(-0.35,0.80,0.45)), v=normalize(-v_pos), h=normalize(l+v);
            float rough=mat.a>0.0?mat.r:1.0, metal=mat.a>0.0?mat.g:0.0, emit=mat.a>0.0?mat.b:0.0;
            float diff=0.35+0.65*max(dot(n,l),0.0);
            float spec=pow(max(dot(n,h),0.0),mix(96.0,2.0,rough))*(1.0-rough);
            return albedo*(1.0-metal)*diff+mix(vec3(0.04),albedo,metal)*spec+albedo*emit;
        }
        void main() {
            vec4 c = texture(u_Textures, v_texCoord);
            if (c.a < u_AlphaCutoff) discard;
            fragColor = vec4(materialLight(c.rgb) * v_color * u_Light, c.a);
        }
        """;
}
