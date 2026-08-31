package de.skyengine.graphics.blockentity;

import de.skyengine.core.file.FileHandle;
import de.skyengine.core.file.FileType;
import de.skyengine.game.world.block.BlockPos;
import de.skyengine.game.world.block.Direction;
import de.skyengine.game.world.block.entity.BlockEntity;
import de.skyengine.game.world.block.entity.EnergyCubeBlockEntity;
import de.skyengine.game.world.block.entity.EnergySideMode;
import de.skyengine.game.world.block.entity.RelativeSide;
import de.skyengine.game.world.block.model.BakedQuad;
import de.skyengine.game.world.block.model.BlockModels;
import de.skyengine.game.world.block.model.ModelLoader;
import de.skyengine.graphics.BlockStateMesh;
import de.skyengine.graphics.camera.Camera;
import de.skyengine.graphics.shader.Shader;
import de.skyengine.graphics.shader.ShaderProgram;
import de.skyengine.graphics.shader.ShaderType;
import de.skyengine.graphics.texture.BlockTextureAtlas;
import de.skyengine.graphics.texture.Texture;
import de.skyengine.graphics.texture.TextureArray;
import de.skyengine.graphics.texture.TextureWrap;
import org.joml.Matrix4f;
import org.joml.Vector3d;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Mekanism 1.21.1 Basic Energy Cube model, side states and animated energy core. */
public final class EnergyCubeRenderer implements BlockEntityRenderer {
    private static final String MODEL = "block/mekanism/energy_cube_basic";
    private static final RelativeSide[] SIDES = RelativeSide.values();
    private static final float INV_SQRT_TWO = (float) (1.0 / Math.sqrt(2.0));

    private final TextureArray textures;
    private final Map<Integer, StateMeshes> meshes = new HashMap<>();
    private final Matrix4f model = new Matrix4f();
    private ShaderProgram blockShader, coreShader;
    private Texture coreTexture;
    private Mesh coreMesh;

    public EnergyCubeRenderer(TextureArray textures) {
        this.textures = textures;
    }

    @Override
    public void init() {
        this.blockShader = new ShaderProgram(
                new Shader(BLOCK_VERTEX, ShaderType.VERTEX),
                new Shader(BLOCK_FRAGMENT, ShaderType.FRAGMENT));
        this.blockShader.bind();
        this.blockShader.setUniformi("u_Textures", 0);
        this.blockShader.setUniformi("u_NormalTextures", 1);
        this.blockShader.setUniformi("u_MaterialTextures", 2);
        this.blockShader.unbind();

        this.coreShader = new ShaderProgram(
                new Shader(CORE_VERTEX, ShaderType.VERTEX),
                new Shader(CORE_FRAGMENT, ShaderType.FRAGMENT));
        this.coreShader.bind();
        this.coreShader.setUniformi("u_Texture", 0);
        this.coreShader.unbind();
        this.coreTexture = new Texture(new FileHandle(
                "game/textures/render/mekanism/energy_core.png", FileType.RESOURCE), false);
        // ModelPart's 16px cube deliberately addresses 0..64px on Mekanism's 32px sheet.
        this.coreTexture.setWrap(TextureWrap.REPEAT, TextureWrap.REPEAT);
        this.coreMesh = new Mesh(coreVertices(), 8);
    }

    @Override
    public void render(BlockEntity be, Camera camera, float partialTick, float light) {
        EnergyCubeBlockEntity cube = (EnergyCubeBlockEntity) be;
        Vector3d cam = camera.getPosition();
        BlockPos pos = cube.getPos();
        float x = (float) (pos.x() - cam.x);
        float y = (float) (pos.y() - cam.y);
        float z = (float) (pos.z() - cam.z);

        this.model.translation(x, y, z);
        applyFacing(this.model, cube.getFacing());
        drawOuter(meshFor(cube), camera.getProjectionViewMatrix(), this.model, light);

        float fill = cube.getEnergy() / (float) cube.getCapacity();
        if (fill > 0.0001F) drawCore(camera.getProjectionViewMatrix(), x, y, z, fill);
    }

    private StateMeshes meshFor(EnergyCubeBlockEntity cube) {
        int key = 0;
        int factor = 1;
        for (RelativeSide side : SIDES) {
            key += cube.getSideMode(side).ordinal() * factor;
            factor *= EnergySideMode.values().length;
        }
        return this.meshes.computeIfAbsent(key, ignored -> buildMeshes(cube));
    }

    private static StateMeshes buildMeshes(EnergyCubeBlockEntity cube) {
        EnergySideMode[] modes = new EnergySideMode[SIDES.length];
        for (RelativeSide side : SIDES) modes[side.ordinal()] = cube.getSideMode(side);
        return buildMeshes(modes);
    }

    private static StateMeshes buildMeshes(EnergySideMode[] modes) {
        List<BakedQuad[]> normal = new ArrayList<>();
        List<BakedQuad[]> emissive = new ArrayList<>();
        normal.add(ModelLoader.bakeGroup(MODEL, "frame").quads());

        for (RelativeSide side : SIDES) {
            String prefix = side.name().toLowerCase();
            BakedQuad[] leds = ModelLoader.bakeGroup(MODEL, prefix + "LEDs").quads();
            BakedQuad[] port = ModelLoader.bakeGroup(MODEL, prefix + "Port").quads();
            switch (modes[side.ordinal()]) {
                case DISABLED -> normal.add(leds);
                case INPUT -> {
                    normal.add(leds);
                    normal.add(port);
                }
                case OUTPUT -> {
                    emissive.add(shiftU(leds, -0.125F));
                    emissive.add(fullBright(port));
                }
            }
        }
        return new StateMeshes(mesh(normal), mesh(emissive));
    }

    private static Mesh mesh(List<BakedQuad[]> parts) {
        if (parts.isEmpty()) return null;
        BakedQuad[] quads = BlockModels.concat(parts.toArray(new BakedQuad[0][]));
        if (quads.length == 0) return null;
        return new Mesh(BlockStateMesh.interleave(quads), BlockStateMesh.FLOATS_PER_VERTEX);
    }

    /** Mekanism's active LED texture occupies the frame an eighth of a sprite to the left. */
    static BakedQuad[] shiftU(BakedQuad[] source, float offset) {
        BakedQuad[] shifted = new BakedQuad[source.length];
        for (int i = 0; i < source.length; i++) {
            BakedQuad quad = source[i];
            float[] vertices = quad.vertices().clone();
            for (int at = 3; at < vertices.length; at += 5) vertices[at] += offset;
            shifted[i] = new BakedQuad(vertices, quad.textureLayer(), quad.cullFace(), quad.face(),
                    1.0F, quad.tint(), quad.tintType());
        }
        return shifted;
    }

    static BakedQuad[] fullBright(BakedQuad[] source) {
        BakedQuad[] bright = new BakedQuad[source.length];
        for (int i = 0; i < source.length; i++) {
            BakedQuad quad = source[i];
            bright[i] = new BakedQuad(quad.vertices(), quad.textureLayer(), quad.cullFace(), quad.face(),
                    1.0F, quad.tint(), quad.tintType());
        }
        return bright;
    }

    private void drawOuter(StateMeshes state, Matrix4f projectionView, Matrix4f transform, float light) {
        this.blockShader.bind();
        this.blockShader.setUniformMatrix4f("u_ProjectionView", projectionView);
        this.blockShader.setUniformMatrix4f("u_Model", transform);
        this.blockShader.setUniformf("u_AlphaCutoff", 0.5F);
        this.textures.bind(0);
        BlockTextureAtlas.bindOptionalMaterials(this.blockShader);
        if (state.normal != null) {
            this.blockShader.setUniformf("u_Light", light);
            state.normal.render();
        }
        if (state.emissive != null) {
            this.blockShader.setUniformf("u_Light", 1.0F);
            state.emissive.render();
        }
        this.blockShader.unbind();
    }

    private void drawCore(Matrix4f projectionView, float x, float y, float z, float fill) {
        boolean blend = GL11.glIsEnabled(GL11.GL_BLEND);
        boolean cull = GL11.glIsEnabled(GL11.GL_CULL_FACE);
        int srcRgb = GL11.glGetInteger(GL14.GL_BLEND_SRC_RGB);
        int dstRgb = GL11.glGetInteger(GL14.GL_BLEND_DST_RGB);
        int srcAlpha = GL11.glGetInteger(GL14.GL_BLEND_SRC_ALPHA);
        int dstAlpha = GL11.glGetInteger(GL14.GL_BLEND_DST_ALPHA);

        GL11.glEnable(GL11.GL_BLEND);
        GL14.glBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA,
                GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glDisable(GL11.GL_CULL_FACE);

        float ticks = (float) (System.nanoTime() * 20.0E-9);
        float scaledTicks = 4.0F * ticks;
        this.model.identity()
                .translate(x + 0.5F, y + 0.5F, z + 0.5F)
                .scale(0.4F)
                .translate(0, (float) Math.sin(Math.toRadians(3.0F * ticks)) / 7.0F, 0)
                .rotateY((float) Math.toRadians(scaledTicks))
                .rotate((float) Math.toRadians(36.0F + scaledTicks), 0, INV_SQRT_TWO, INV_SQRT_TWO);

        this.coreShader.bind();
        this.coreShader.setUniformMatrix4f("u_ProjectionView", projectionView);
        this.coreShader.setUniformMatrix4f("u_Model", this.model);
        this.coreShader.setUniformf("u_Alpha", fill);
        this.coreTexture.bind(0);
        this.coreMesh.render();
        this.coreShader.unbind();

        GL14.glBlendFuncSeparate(srcRgb, dstRgb, srcAlpha, dstAlpha);
        if (cull) GL11.glEnable(GL11.GL_CULL_FACE);
        if (!blend) GL11.glDisable(GL11.GL_BLEND);
    }

    private static void applyFacing(Matrix4f matrix, Direction facing) {
        matrix.translate(0.5F, 0.5F, 0.5F);
        switch (facing) {
            case EAST -> matrix.rotateY((float) -Math.PI / 2);
            case SOUTH -> matrix.rotateY((float) Math.PI);
            case WEST -> matrix.rotateY((float) Math.PI / 2);
            case UP -> matrix.rotateX((float) Math.PI / 2);
            case DOWN -> matrix.rotateX((float) -Math.PI / 2);
            default -> { }
        }
        matrix.translate(-0.5F, -0.5F, -0.5F);
    }

    @Override
    public boolean hasIcon() {
        return true;
    }

    @Override
    public void renderIcon(Matrix4f mvp, Matrix4f itemTransform) {
        drawOuter(defaultMeshes(), mvp, new Matrix4f(), 1.0F);
    }

    @Override
    public void renderHeld(Matrix4f mvp, float light) {
        drawOuter(defaultMeshes(), mvp, new Matrix4f(), light);
    }

    private StateMeshes defaultMeshes() {
        return this.meshes.computeIfAbsent(-1, ignored -> {
            EnergySideMode[] modes = new EnergySideMode[SIDES.length];
            for (RelativeSide side : SIDES) modes[side.ordinal()] = EnergySideMode.INPUT;
            modes[RelativeSide.FRONT.ordinal()] = EnergySideMode.OUTPUT;
            return buildMeshes(modes);
        });
    }

    @Override
    public void dispose() {
        this.meshes.values().forEach(StateMeshes::dispose);
        this.meshes.clear();
        if (this.coreMesh != null) this.coreMesh.dispose();
        if (this.coreTexture != null) this.coreTexture.dispose();
        if (this.blockShader != null) this.blockShader.dispose();
        if (this.coreShader != null) this.coreShader.dispose();
    }

    /** Vanilla ModelPart box UV layout for a 16px cube on Mekanism's 32x32 core texture. */
    private static float[] coreVertices() {
        float[] out = new float[36 * 8];
        int[] at = {0};
        coreFace(out, at, -.5F,.5F,.5F,  .5F,.5F,.5F,  .5F,.5F,-.5F, -.5F,.5F,-.5F, 32,0,16,16, false);
        coreFace(out, at, -.5F,-.5F,-.5F, .5F,-.5F,-.5F, .5F,-.5F,.5F, -.5F,-.5F,.5F, 16,0,16,16, false);
        coreFace(out, at, .5F,-.5F,-.5F, -.5F,-.5F,-.5F, -.5F,.5F,-.5F, .5F,.5F,-.5F, 16,16,16,16, true);
        coreFace(out, at, -.5F,-.5F,.5F, .5F,-.5F,.5F, .5F,.5F,.5F, -.5F,.5F,.5F, 48,16,16,16, true);
        coreFace(out, at, -.5F,-.5F,-.5F, -.5F,-.5F,.5F, -.5F,.5F,.5F, -.5F,.5F,-.5F, 0,16,16,16, false);
        coreFace(out, at, .5F,-.5F,.5F, .5F,-.5F,-.5F, .5F,.5F,-.5F, .5F,.5F,.5F, 32,16,16,16, false);
        return out;
    }

    private static void coreFace(float[] out, int[] at,
                                 float ax,float ay,float az, float bx,float by,float bz,
                                 float cx,float cy,float cz, float dx,float dy,float dz,
                                 int texX,int texY,int texW,int texH, boolean flipU) {
        float u0 = texX / 32.0F, v0 = texY / 32.0F;
        float u1 = (texX + texW) / 32.0F, v1 = (texY + texH) / 32.0F;
        float left = flipU ? u1 : u0, right = flipU ? u0 : u1;
        coreVertex(out, at, ax,ay,az,left,v1);
        coreVertex(out, at, bx,by,bz,right,v1);
        coreVertex(out, at, cx,cy,cz,right,v0);
        coreVertex(out, at, ax,ay,az,left,v1);
        coreVertex(out, at, cx,cy,cz,right,v0);
        coreVertex(out, at, dx,dy,dz,left,v0);
    }

    private static void coreVertex(float[] out, int[] at, float x,float y,float z,float u,float v) {
        out[at[0]++] = x; out[at[0]++] = y; out[at[0]++] = z;
        out[at[0]++] = u; out[at[0]++] = v;
        out[at[0]++] = 0; out[at[0]++] = 1; out[at[0]++] = 0;
    }

    private record StateMeshes(Mesh normal, Mesh emissive) {
        void dispose() {
            if (normal != null) normal.dispose();
            if (emissive != null) emissive.dispose();
        }
    }

    private static final class Mesh {
        final int vao, vbo, count;

        Mesh(float[] source, int strideFloats) {
            float[] data = source == null ? new float[0] : source;
            this.count = data.length / strideFloats;
            this.vao = GL30.glGenVertexArrays();
            this.vbo = GL15.glGenBuffers();
            GL30.glBindVertexArray(this.vao);
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, this.vbo);
            GL15.glBufferData(GL15.GL_ARRAY_BUFFER, data, GL15.GL_STATIC_DRAW);
            int stride = strideFloats * Float.BYTES;
            GL20.glVertexAttribPointer(0, 3, GL11.GL_FLOAT, false, stride, 0);
            if (strideFloats == BlockStateMesh.FLOATS_PER_VERTEX) {
                GL20.glVertexAttribPointer(1, 3, GL11.GL_FLOAT, false, stride, 3L * Float.BYTES);
                GL20.glVertexAttribPointer(2, 3, GL11.GL_FLOAT, false, stride, 6L * Float.BYTES);
            } else {
                GL20.glVertexAttribPointer(1, 2, GL11.GL_FLOAT, false, stride, 3L * Float.BYTES);
            }
            GL20.glEnableVertexAttribArray(0);
            GL20.glEnableVertexAttribArray(1);
            if (strideFloats == BlockStateMesh.FLOATS_PER_VERTEX) GL20.glEnableVertexAttribArray(2);
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

    private static final String BLOCK_VERTEX = """
            #version 460 core
            layout(location=0) in vec3 a_position;
            layout(location=1) in vec3 a_texCoord;
            layout(location=2) in vec3 a_color;
            uniform mat4 u_ProjectionView;
            uniform mat4 u_Model;
            out vec3 v_texCoord;
            out vec3 v_color;
            void main(){v_texCoord=a_texCoord;v_color=a_color;gl_Position=u_ProjectionView*u_Model*vec4(a_position,1);}
            """;
    private static final String BLOCK_FRAGMENT = """
            #version 460 core
            in vec3 v_texCoord;
            in vec3 v_color;
            uniform sampler2DArray u_Textures;
            uniform sampler2DArray u_NormalTextures;
            uniform sampler2DArray u_MaterialTextures;
            uniform int u_PbrEnabled;
            uniform float u_Light;
            uniform float u_AlphaCutoff;
            layout(location = 0) out vec4 fragColor;
            void main(){vec4 c=texture(u_Textures,v_texCoord);if(c.a<u_AlphaCutoff)discard;fragColor=vec4(c.rgb*v_color*u_Light,c.a);}
            """;
    private static final String CORE_VERTEX = """
            #version 330 core
            layout(location=0) in vec3 a_position;
            layout(location=1) in vec2 a_uv;
            uniform mat4 u_ProjectionView;
            uniform mat4 u_Model;
            out vec2 v_uv;
            void main(){v_uv=a_uv;gl_Position=u_ProjectionView*u_Model*vec4(a_position,1);}
            """;
    private static final String CORE_FRAGMENT = """
            #version 330 core
            in vec2 v_uv;
            uniform sampler2D u_Texture;
            uniform float u_Alpha;
            layout(location = 0) out vec4 fragColor;
            void main(){vec4 c=texture(u_Texture,v_uv);if(c.a<.01)discard;fragColor=vec4(c.rgb*vec3(.37255,1.0,.72157),c.a*u_Alpha);}
            """;
}
