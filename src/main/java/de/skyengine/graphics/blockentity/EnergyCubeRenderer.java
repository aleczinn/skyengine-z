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
import org.joml.Matrix4f;
import org.joml.Vector3d;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Mekanism-compatible Basic Energy Cube model, side ports and animated energy core. */
public final class EnergyCubeRenderer implements BlockEntityRenderer {
    private static final String MODEL = "block/mekanism/energy_cube_basic";
    private static final RelativeSide[] SIDES = RelativeSide.values();

    private final TextureArray textures;
    private final Map<Integer, Mesh> meshes = new HashMap<>();
    private final Matrix4f model = new Matrix4f();
    private ShaderProgram blockShader, coreShader;
    private Texture coreTexture;
    private Mesh coreMesh;

    public EnergyCubeRenderer(TextureArray textures) { this.textures = textures; }

    @Override public void init() {
        this.blockShader = new ShaderProgram(new Shader(BLOCK_VERTEX, ShaderType.VERTEX), new Shader(BLOCK_FRAGMENT, ShaderType.FRAGMENT));
        this.blockShader.bind();
        this.blockShader.setUniformi("u_Textures", 0);
        this.blockShader.setUniformi("u_NormalTextures", 1);
        this.blockShader.setUniformi("u_MaterialTextures", 2);
        this.blockShader.unbind();
        this.coreShader = new ShaderProgram(new Shader(CORE_VERTEX, ShaderType.VERTEX), new Shader(CORE_FRAGMENT, ShaderType.FRAGMENT));
        this.coreShader.bind(); this.coreShader.setUniformi("u_Texture", 0); this.coreShader.unbind();
        this.coreTexture = new Texture(new FileHandle("game/textures/render/mekanism/energy_core.png", FileType.RESOURCE), false);
        this.coreMesh = new Mesh(coreVertices(), 8);
    }

    @Override public void render(BlockEntity be, Camera camera, float partialTick, float light) {
        EnergyCubeBlockEntity cube = (EnergyCubeBlockEntity) be;
        Vector3d cam = camera.getPosition();
        BlockPos pos = cube.getPos();
        float x = (float) (pos.x() - cam.x), y = (float) (pos.y() - cam.y), z = (float) (pos.z() - cam.z);
        this.model.translation(x, y, z);
        applyFacing(this.model, cube.getFacing());
        drawOuter(meshFor(cube), camera.getProjectionViewMatrix(), this.model, light);
        float fill = cube.getEnergy() / (float) cube.getCapacity();
        if (fill > .0001f) drawCore(camera.getProjectionViewMatrix(), x, y, z, fill, light);
    }

    private Mesh meshFor(EnergyCubeBlockEntity cube) {
        int key = 0, factor = 1;
        for (RelativeSide side : SIDES) { key += cube.getSideMode(side).ordinal() * factor; factor *= 3; }
        return this.meshes.computeIfAbsent(key, ignored -> buildMesh(cube));
    }

    private static Mesh buildMesh(EnergyCubeBlockEntity cube) {
        List<BakedQuad[]> parts = new ArrayList<>();
        parts.add(ModelLoader.bakeGroup(MODEL, "frame").quads());
        for (RelativeSide side : SIDES) {
            EnergySideMode mode = cube.getSideMode(side);
            String prefix = side.name().toLowerCase();
            BakedQuad[] leds = ModelLoader.bakeGroup(MODEL, prefix + "LEDs").quads();
            parts.add(mode == EnergySideMode.OUTPUT ? active(leds) : leds);
            if (mode != EnergySideMode.DISABLED) {
                BakedQuad[] port = ModelLoader.bakeGroup(MODEL, prefix + "Port").quads();
                parts.add(mode == EnergySideMode.OUTPUT ? active(port) : port);
            }
        }
        return new Mesh(BlockStateMesh.interleave(BlockModels.concat(parts.toArray(new BakedQuad[0][]))), BlockStateMesh.FLOATS_PER_VERTEX);
    }

    private static BakedQuad[] active(BakedQuad[] source) {
        BakedQuad[] result = new BakedQuad[source.length];
        for (int i = 0; i < source.length; i++) {
            BakedQuad q = source[i];
            float[] vertices = q.vertices().clone();
            for (int p = 0; p < vertices.length; p += 5) vertices[p + 3] -= .125f;
            result[i] = new BakedQuad(vertices, q.textureLayer(), q.cullFace(), q.face(), 1f, q.tint(), q.tintType());
        }
        return result;
    }

    private void drawOuter(Mesh mesh, Matrix4f projectionView, Matrix4f transform, float light) {
        this.blockShader.bind();
        this.blockShader.setUniformMatrix4f("u_ProjectionView", projectionView);
        this.blockShader.setUniformMatrix4f("u_Model", transform);
        this.blockShader.setUniformf("u_Light", light);
        this.blockShader.setUniformf("u_AlphaCutoff", .5f);
        this.textures.bind(0);
        BlockTextureAtlas.bindOptionalMaterials(this.blockShader);
        mesh.render();
        this.blockShader.unbind();
    }

    private void drawCore(Matrix4f projectionView, float x, float y, float z, float fill, float light) {
        boolean blend = GL11.glIsEnabled(GL11.GL_BLEND), cull = GL11.glIsEnabled(GL11.GL_CULL_FACE);
        GL11.glEnable(GL11.GL_BLEND); GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA); GL11.glDisable(GL11.GL_CULL_FACE);
        float scale = .32f + .20f * fill;
        float bob = (float) Math.sin(System.nanoTime() * 1.2E-9) * .025f;
        this.model.identity().translate(x + .5f, y + .5f + bob, z + .5f).rotateY((float) (System.nanoTime() * 7E-10))
                .scale(scale).translate(-.5f, -.5f, -.5f);
        this.coreShader.bind();
        this.coreShader.setUniformMatrix4f("u_ProjectionView", projectionView);
        this.coreShader.setUniformMatrix4f("u_Model", this.model);
        this.coreShader.setUniformf("u_Light", Math.max(light, .65f));
        this.coreShader.setUniformf("u_Alpha", .35f + .45f * fill);
        this.coreTexture.bind(0); this.coreMesh.render(); this.coreShader.unbind();
        if (cull) GL11.glEnable(GL11.GL_CULL_FACE); if (!blend) GL11.glDisable(GL11.GL_BLEND);
    }

    private static void applyFacing(Matrix4f matrix, Direction facing) {
        matrix.translate(.5f, .5f, .5f);
        switch (facing) {
            case EAST -> matrix.rotateY((float) -Math.PI / 2);
            case SOUTH -> matrix.rotateY((float) Math.PI);
            case WEST -> matrix.rotateY((float) Math.PI / 2);
            case UP -> matrix.rotateX((float) Math.PI / 2);
            case DOWN -> matrix.rotateX((float) -Math.PI / 2);
            default -> { }
        }
        matrix.translate(-.5f, -.5f, -.5f);
    }

    @Override public boolean hasIcon() { return true; }
    @Override public void renderIcon(Matrix4f mvp, Matrix4f itemTransform) { drawOuter(defaultMesh(), mvp, new Matrix4f(), 1f); }
    @Override public void renderHeld(Matrix4f mvp, float light) { drawOuter(defaultMesh(), mvp, new Matrix4f(), light); }

    private Mesh defaultMesh() {
        return this.meshes.computeIfAbsent(-1, ignored -> {
            List<BakedQuad[]> parts = new ArrayList<>(); parts.add(ModelLoader.bakeGroup(MODEL, "frame").quads());
            for (RelativeSide side : SIDES) {
                String p = side.name().toLowerCase();
                parts.add(ModelLoader.bakeGroup(MODEL, p + "LEDs").quads());
                parts.add(ModelLoader.bakeGroup(MODEL, p + "Port").quads());
            }
            return new Mesh(BlockStateMesh.interleave(BlockModels.concat(parts.toArray(new BakedQuad[0][]))), BlockStateMesh.FLOATS_PER_VERTEX);
        });
    }

    @Override public void dispose() {
        this.meshes.values().forEach(Mesh::dispose); this.meshes.clear();
        if (this.coreMesh != null) this.coreMesh.dispose(); if (this.coreTexture != null) this.coreTexture.dispose();
        if (this.blockShader != null) this.blockShader.dispose(); if (this.coreShader != null) this.coreShader.dispose();
    }

    private static float[] coreVertices() {
        float[] p = {0,0,0, 1,0,0, 1,1,0, 0,1,0, 0,0,1, 1,0,1, 1,1,1, 0,1,1};
        int[][] faces = {{0,1,2,3},{5,4,7,6},{4,0,3,7},{1,5,6,2},{3,2,6,7},{4,5,1,0}};
        float[] uv = {0,1, 1,1, 1,0, 0,0}; float[] out = new float[36 * 8]; int at = 0; int[] order = {0,1,2,0,2,3};
        for (int[] face : faces) for (int corner : order) {
            int vi = face[corner] * 3; out[at++] = p[vi]; out[at++] = p[vi+1]; out[at++] = p[vi+2];
            out[at++] = uv[corner*2]; out[at++] = uv[corner*2+1]; out[at++] = 0; out[at++] = 1; out[at++] = 0;
        }
        return out;
    }

    private static final class Mesh {
        final int vao, vbo, count;
        Mesh(float[] source, int strideFloats) {
            float[] data = source == null ? new float[0] : source; this.count = data.length / strideFloats;
            this.vao = GL30.glGenVertexArrays(); this.vbo = GL15.glGenBuffers();
            GL30.glBindVertexArray(this.vao); GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, this.vbo); GL15.glBufferData(GL15.GL_ARRAY_BUFFER, data, GL15.GL_STATIC_DRAW);
            int stride = strideFloats * Float.BYTES; GL20.glVertexAttribPointer(0, 3, GL11.GL_FLOAT, false, stride, 0);
            if (strideFloats == BlockStateMesh.FLOATS_PER_VERTEX) {
                GL20.glVertexAttribPointer(1, 3, GL11.GL_FLOAT, false, stride, 3L * Float.BYTES);
                GL20.glVertexAttribPointer(2, 3, GL11.GL_FLOAT, false, stride, 6L * Float.BYTES);
            } else GL20.glVertexAttribPointer(1, 2, GL11.GL_FLOAT, false, stride, 3L * Float.BYTES);
            GL20.glEnableVertexAttribArray(0); GL20.glEnableVertexAttribArray(1);
            if (strideFloats == BlockStateMesh.FLOATS_PER_VERTEX) GL20.glEnableVertexAttribArray(2); GL30.glBindVertexArray(0);
        }
        void render() { GL30.glBindVertexArray(this.vao); GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, this.count); }
        void dispose() { GL30.glDeleteVertexArrays(this.vao); GL15.glDeleteBuffers(this.vbo); }
    }

    private static final String BLOCK_VERTEX = """
            #version 460 core
            layout(location=0) in vec3 a_position; layout(location=1) in vec3 a_texCoord; layout(location=2) in vec3 a_color;
            uniform mat4 u_ProjectionView; uniform mat4 u_Model; out vec3 v_texCoord; out vec3 v_color;
            void main(){v_texCoord=a_texCoord;v_color=a_color;gl_Position=u_ProjectionView*u_Model*vec4(a_position,1);}
            """;
    private static final String BLOCK_FRAGMENT = """
            #version 460 core
            in vec3 v_texCoord; in vec3 v_color; uniform sampler2DArray u_Textures; uniform sampler2DArray u_NormalTextures;
            uniform sampler2DArray u_MaterialTextures; uniform int u_PbrEnabled; uniform float u_Light; uniform float u_AlphaCutoff; out vec4 fragColor;
            void main(){vec4 c=texture(u_Textures,v_texCoord);if(c.a<u_AlphaCutoff)discard;fragColor=vec4(c.rgb*v_color*u_Light,c.a);}
            """;
    private static final String CORE_VERTEX = """
            #version 330 core
            layout(location=0) in vec3 a_position; layout(location=1) in vec2 a_uv; uniform mat4 u_ProjectionView; uniform mat4 u_Model; out vec2 v_uv;
            void main(){v_uv=a_uv;gl_Position=u_ProjectionView*u_Model*vec4(a_position,1);}
            """;
    private static final String CORE_FRAGMENT = """
            #version 330 core
            in vec2 v_uv; uniform sampler2D u_Texture; uniform float u_Light; uniform float u_Alpha; out vec4 fragColor;
            void main(){vec4 c=texture(u_Texture,v_uv);if(c.a<.01)discard;fragColor=vec4(c.rgb*u_Light,c.a*u_Alpha);}
            """;
}
