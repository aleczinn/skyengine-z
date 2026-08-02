package de.skyengine.graphics.blockentity;

import de.skyengine.game.world.block.BlockPos;
import de.skyengine.game.world.block.Blocks;
import de.skyengine.game.world.block.Direction;
import de.skyengine.game.world.block.RenderLayer;
import de.skyengine.game.world.block.entity.BlockEntity;
import de.skyengine.game.world.block.entity.PistonMovingBlockEntity;
import de.skyengine.game.world.block.state.PistonType;
import de.skyengine.game.world.block.state.Properties;
import de.skyengine.graphics.BlockStateMesh;
import de.skyengine.graphics.camera.Camera;
import de.skyengine.graphics.shader.Shader;
import de.skyengine.graphics.shader.ShaderProgram;
import de.skyengine.graphics.shader.ShaderType;
import de.skyengine.graphics.texture.TextureArray;
import org.joml.Matrix4f;
import org.joml.Vector3d;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

/**
 * Rendert den gleitenden Block eines Kolben-Schubs: das gebackene Modell des transportierten
 * States, kamerarelativ um {@code (1 − progress)} entgegen der Bewegungsrichtung versetzt
 * (Muster {@code EntityRenderer}-FallingBlock, Daten-Bau über {@link BlockStateMesh}).
 *
 * <p>Sonderfall Retract-Source (Basis zieht den Arm ein): der Basis-State steht STATISCH in
 * seiner Zelle — sonst wäre die Basis 2 Ticks unsichtbar — und zusätzlich gleitet der
 * passende {@code piston_head} von der Kopf-Zelle zurück.
 *
 * <p>Der Dispatcher-Cull-Margin von 1.0 deckt den maximalen Versatz von genau 1 Block ab;
 * das Licht kommt (wie bei allen BE-Renderern) aus der BE-Zelle.
 */
public final class PistonMovingRenderer implements BlockEntityRenderer {

    private static final float CUTOUT_ALPHA = 0.5f;
    private static final float TRANSLUCENT_ALPHA = 0.001f;

    private final TextureArray textures;
    private ShaderProgram shader;
    private int locProjectionView, locModel, locLight, locAlphaCutoff;

    private final de.skyengine.utils.collect.LongObjMap<Object> cache =
            new de.skyengine.utils.collect.LongObjMap<>(16);
    private static final Object NO_MESH = new Object();

    private final Matrix4f model = new Matrix4f();

    public PistonMovingRenderer(TextureArray textures) {
        this.textures = textures;
    }

    @Override
    public void init() {
        this.shader = new ShaderProgram(
                new Shader(VERTEX, ShaderType.VERTEX),
                new Shader(FRAGMENT, ShaderType.FRAGMENT));
        this.locProjectionView = this.shader.getUniformLocation("u_ProjectionView");
        this.locModel = this.shader.getUniformLocation("u_Model");
        this.locLight = this.shader.getUniformLocation("u_Light");
        this.locAlphaCutoff = this.shader.getUniformLocation("u_AlphaCutoff");
        this.shader.bind();
        this.shader.setUniformi("u_Textures", 0);
        this.shader.unbind();
    }

    @Override
    public void render(BlockEntity be, Camera camera, float partialTick, float light) {
        if (!(be instanceof PistonMovingBlockEntity moving)) return;

        this.shader.bind();
        this.shader.setUniformMatrix4f(this.locProjectionView, camera.getProjectionViewMatrix());
        this.shader.setUniformf(this.locLight, light);
        this.shader.setUniformf(this.locAlphaCutoff, CUTOUT_ALPHA);
        this.textures.bind(0);

        Vector3d cam = camera.getPosition();
        BlockPos pos = moving.getPos();
        float baseX = (float) (pos.x() - cam.x);
        float baseY = (float) (pos.y() - cam.y);
        float baseZ = (float) (pos.z() - cam.z);
        float back = 1.0f - moving.getProgress(partialTick);
        Direction f = moving.getFacing();

        if (moving.isSource() && !moving.isExtending()) {
            this.drawState(moving.getMovedStateId(), baseX, baseY, baseZ);
            this.drawState(this.headStateFor(moving),
                    baseX + f.offsetX() * back, baseY + f.offsetY() * back, baseZ + f.offsetZ() * back);
        } else {
            Direction d = moving.isExtending() ? f : f.opposite();
            this.drawState(moving.getMovedStateId(),
                    baseX - d.offsetX() * back, baseY - d.offsetY() * back, baseZ - d.offsetZ() * back);
        }
        this.shader.unbind();
    }

    /** Der zum eingezogenen Basis-State passende Kopf (für die Rückzieh-Optik). */
    private int headStateFor(PistonMovingBlockEntity moving) {
        boolean sticky = Blocks.getState(moving.getMovedStateId()).getBlock()
                == Blocks.getState(Blocks.STICKY_PISTON).getBlock();
        return Blocks.getState(Blocks.PISTON_HEAD)
                .with(Properties.FACING_ALL, moving.getFacing())
                .with(Properties.PISTON_TYPE, sticky ? PistonType.STICKY : PistonType.NORMAL)
                .getId();
    }

    private void drawState(int stateId, float ox, float oy, float oz) {
        Mesh mesh = this.meshFor(stateId);
        if (mesh == null) return;
        boolean translucent = Blocks.getState(stateId).getRenderLayer() == RenderLayer.TRANSLUCENT;
        if (translucent) {
            GL11.glEnable(GL11.GL_BLEND);
            this.shader.setUniformf(this.locAlphaCutoff, TRANSLUCENT_ALPHA);
        }
        this.model.translation(ox, oy, oz);
        this.shader.setUniformMatrix4f(this.locModel, this.model);
        mesh.render();
        if (translucent) {
            GL11.glDisable(GL11.GL_BLEND);
            this.shader.setUniformf(this.locAlphaCutoff, CUTOUT_ALPHA);
        }
    }

    private Mesh meshFor(int stateId) {
        Object cached = this.cache.get(stateId);
        if (cached != null) return cached == NO_MESH ? null : (Mesh) cached;
        float[] data = BlockStateMesh.interleave(stateId);
        Mesh mesh = data == null ? null : new Mesh(data);
        this.cache.put(stateId, mesh == null ? NO_MESH : mesh);
        return mesh;
    }

    @Override
    public void dispose() {
        for (int i = 0, n = this.cache.tableSize(); i < n; i++) {
            Object cached = this.cache.valueAt(i);
            if (cached != null && cached != NO_MESH) ((Mesh) cached).dispose();
        }
        this.cache.clear();
        if (this.shader != null) this.shader.dispose();
    }

    /* --- kleine VAO/VBO-Hülle (Layout wie EntityRenderer/BlockStateMesh) --- */
    private static final class Mesh {
        private final int vao, vbo, count;

        Mesh(float[] data) {
            this.count = data.length / BlockStateMesh.FLOATS_PER_VERTEX;
            this.vao = GL30.glGenVertexArrays();
            this.vbo = GL15.glGenBuffers();
            GL30.glBindVertexArray(this.vao);
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, this.vbo);
            GL15.glBufferData(GL15.GL_ARRAY_BUFFER, data, GL15.GL_STATIC_DRAW);
            de.skyengine.graphics.GlDebug.labelBuffer(this.vbo, "PistonMovingRenderer Mesh-VBO");
            int stride = BlockStateMesh.FLOATS_PER_VERTEX * Float.BYTES;
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
        /* Licht der BE-Zelle, fertig durch die Kurve gerechnet (ChunkRenderer.lightFactor). */
        uniform float u_Light;
        /* Wie im ChunkRenderer: 0.5 = harter Cutout, 0.001 = praktisch aus fürs Blending. */
        uniform float u_AlphaCutoff;
        out vec4 fragColor;
        void main() {
            vec4 c = texture(u_Textures, v_texCoord);
            if (c.a < u_AlphaCutoff) discard;
            fragColor = vec4(c.rgb * v_color * u_Light, c.a);
        }
        """;
}
