package de.skyengine.graphics.particle;

import de.skyengine.game.world.particle.ParticleEngine;
import de.skyengine.graphics.GlDebug;
import de.skyengine.graphics.GlState;
import de.skyengine.graphics.camera.Camera;
import de.skyengine.graphics.shader.Shader;
import de.skyengine.graphics.shader.ShaderProgram;
import de.skyengine.graphics.shader.ShaderType;
import de.skyengine.graphics.texture.TextureArray;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL32;
import org.lwjgl.opengl.GL33;
import org.lwjgl.opengl.GL44;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;

/** Zwei instanzierte Billboard-Pässe über einen persistent gemappten Drei-Frame-Ring. */
public final class ParticleRenderer {

    private static final int SLOTS = 3;
    private static final int FLOATS = ParticleEngine.INSTANCE_FLOATS;
    private static final int SEGMENT_FLOATS = ParticleEngine.MAX_PARTICLES * FLOATS;
    private static final int SLOT_FLOATS = SEGMENT_FLOATS * 2;
    private static final int MAP_FLAGS = GL30.GL_MAP_WRITE_BIT
            | GL44.GL_MAP_PERSISTENT_BIT | GL44.GL_MAP_COHERENT_BIT;

    private final ParticleEngine particles;
    private final TextureArray textures;
    private final long[] fences = new long[SLOTS];
    private final FloatBuffer[][] views = new FloatBuffer[SLOTS][2];
    private ShaderProgram shader;
    private int vao, instanceBuffer;
    private ByteBuffer mapped;
    private int slot;
    private long frame;
    private int opaqueCount, translucentCount;
    private boolean prepared;

    public ParticleRenderer(ParticleEngine particles, TextureArray textures) {
        this.particles = particles;
        this.textures = textures;
    }

    public void init() {
        this.shader = new ShaderProgram(new Shader(VERTEX, ShaderType.VERTEX),
                new Shader(FRAGMENT, ShaderType.FRAGMENT));
        this.shader.bind();
        this.shader.setUniformi("u_Textures", 0);
        this.shader.unbind();

        this.vao = GL30.glGenVertexArrays();
        this.instanceBuffer = GL15.glGenBuffers();
        GL30.glBindVertexArray(this.vao);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, this.instanceBuffer);
        long bytes = (long) SLOT_FLOATS * SLOTS * Float.BYTES;
        GL44.glBufferStorage(GL15.GL_ARRAY_BUFFER, bytes, MAP_FLAGS);
        this.mapped = GL30.glMapBufferRange(GL15.GL_ARRAY_BUFFER, 0, bytes, MAP_FLAGS);
        if (this.mapped == null) throw new IllegalStateException("Partikel-Instanzbuffer konnte nicht gemappt werden");
        this.configureAttributes(0L);
        GL30.glBindVertexArray(0);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
        GlDebug.labelBuffer(this.instanceBuffer, "Particle instances");
    }

    public void renderOpaque(Camera camera, float partialTick) {
        this.prepare(camera, partialTick);
        this.draw(camera, false, this.opaqueCount);
    }

    public void renderTranslucent(Camera camera, float partialTick) {
        this.prepare(camera, partialTick);
        this.draw(camera, true, this.translucentCount);
        this.fences[this.slot] = GL32.glFenceSync(GL32.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
        this.frame++;
        this.prepared = false;
    }

    private void prepare(Camera camera, float partialTick) {
        if (this.prepared) return;
        this.slot = (int) (this.frame % SLOTS);
        long fence = this.fences[this.slot];
        if (fence != 0L) {
            int status;
            do {
                status = GL32.glClientWaitSync(fence, GL32.GL_SYNC_FLUSH_COMMANDS_BIT, 1_000_000_000L);
            } while (status == GL32.GL_TIMEOUT_EXPIRED);
            GL32.glDeleteSync(fence);
            this.fences[this.slot] = 0L;
        }
        FloatBuffer opaque = this.view(this.slot, 0);
        opaque.clear();
        this.opaqueCount = this.particles.writeInstances(opaque, camera, partialTick, false);
        FloatBuffer translucent = this.view(this.slot, 1);
        translucent.clear();
        this.translucentCount = this.particles.writeInstances(translucent, camera, partialTick, true);
        this.prepared = true;
    }

    private FloatBuffer view(int slot, int segment) {
        FloatBuffer view = this.views[slot][segment];
        if (view != null) return view;
        long floatOffset = (long) slot * SLOT_FLOATS + (long) segment * SEGMENT_FLOATS;
        view = MemoryUtil.memFloatBuffer(MemoryUtil.memAddress(this.mapped) + floatOffset * Float.BYTES,
                SEGMENT_FLOATS);
        this.views[slot][segment] = view;
        return view;
    }

    private void draw(Camera camera, boolean translucent, int count) {
        if (count <= 0) return;
        boolean cull = GlState.isCullFaceEnabled();
        GlState.disableCullFace();
        if (translucent) {
            GL11.glEnable(GL11.GL_BLEND);
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            GL11.glDepthMask(false);
        }
        this.shader.bind();
        this.shader.setUniformMatrix4f("u_ProjectionView", camera.getProjectionViewMatrix());
        float yaw = (float) Math.toRadians(camera.getYaw());
        float pitch = (float) Math.toRadians(camera.getPitch());
        float cy = (float) Math.cos(yaw), sy = (float) Math.sin(yaw);
        float cp = (float) Math.cos(pitch), sp = (float) Math.sin(pitch);
        this.shader.setUniformVector3f("u_Right", cy, 0F, sy);
        this.shader.setUniformVector3f("u_Up", sy * sp, cp, -cy * sp);
        this.shader.setUniformf("u_AlphaCutoff", translucent ? 0.001F : 0.1F);
        this.textures.bind(0);
        GL30.glBindVertexArray(this.vao);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, this.instanceBuffer);
        long base = ((long) this.slot * SLOT_FLOATS + (translucent ? SEGMENT_FLOATS : 0L)) * Float.BYTES;
        this.configureAttributes(base);
        GL31Compat.drawArraysInstanced(count);
        GL30.glBindVertexArray(0);
        this.shader.unbind();
        if (translucent) {
            GL11.glDepthMask(true);
            GL11.glDisable(GL11.GL_BLEND);
        }
        if (cull) GlState.enableCullFace();
    }

    private void configureAttributes(long base) {
        int stride = FLOATS * Float.BYTES;
        attribute(0, 3, stride, base);
        attribute(1, 2, stride, base + 3L * Float.BYTES);
        attribute(2, 4, stride, base + 5L * Float.BYTES);
        attribute(3, 1, stride, base + 9L * Float.BYTES);
        attribute(4, 4, stride, base + 10L * Float.BYTES);
        attribute(5, 1, stride, base + 14L * Float.BYTES);
    }

    private static void attribute(int index, int size, int stride, long offset) {
        GL20.glEnableVertexAttribArray(index);
        GL20.glVertexAttribPointer(index, size, GL11.GL_FLOAT, false, stride, offset);
        GL33.glVertexAttribDivisor(index, 1);
    }

    public void dispose() {
        for (long fence : this.fences) if (fence != 0L) GL32.glDeleteSync(fence);
        if (this.shader != null) this.shader.dispose();
        if (this.vao != 0) GL30.glDeleteVertexArrays(this.vao);
        if (this.instanceBuffer != 0) {
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, this.instanceBuffer);
            if (this.mapped != null) GL15.glUnmapBuffer(GL15.GL_ARRAY_BUFFER);
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
            GL15.glDeleteBuffers(this.instanceBuffer);
        }
        this.shader = null;
        this.vao = this.instanceBuffer = 0;
        for (int i = 0; i < SLOTS; i++) {
            this.fences[i] = 0L;
            this.views[i][0] = this.views[i][1] = null;
        }
        this.mapped = null;
        this.frame = 0L;
        this.prepared = false;
    }

    /** Hält den eigentlichen Draw-Aufruf aus der Attributkonfiguration heraus. */
    private static final class GL31Compat {
        static void drawArraysInstanced(int count) {
            org.lwjgl.opengl.GL31.glDrawArraysInstanced(GL11.GL_TRIANGLES, 0, 6, count);
        }
    }

    private static final String VERTEX = """
            #version 460 core
            layout(location=0) in vec3 a_Center;
            layout(location=1) in vec2 a_SizeRotation;
            layout(location=2) in vec4 a_UvRect;
            layout(location=3) in float a_Layer;
            layout(location=4) in vec4 a_Color;
            layout(location=5) in float a_Light;
            uniform mat4 u_ProjectionView;
            uniform vec3 u_Right;
            uniform vec3 u_Up;
            out vec3 v_TexCoord;
            out vec4 v_Color;
            const vec2 POS[6] = vec2[6](vec2(-1,-1), vec2(1,-1), vec2(1,1),
                                        vec2(-1,-1), vec2(1,1), vec2(-1,1));
            const vec2 UV[6] = vec2[6](vec2(0,1), vec2(1,1), vec2(1,0),
                                       vec2(0,1), vec2(1,0), vec2(0,0));
            void main() {
                vec2 p = POS[gl_VertexID];
                float c = cos(a_SizeRotation.y), s = sin(a_SizeRotation.y);
                p = mat2(c,-s,s,c) * p;
                vec3 world = a_Center + (u_Right * p.x + u_Up * p.y) * a_SizeRotation.x;
                gl_Position = u_ProjectionView * vec4(world, 1.0);
                v_TexCoord = vec3(mix(a_UvRect.xy, a_UvRect.zw, UV[gl_VertexID]), a_Layer);
                v_Color = vec4(a_Color.rgb * a_Light, a_Color.a);
            }
            """;

    private static final String FRAGMENT = """
            #version 460 core
            in vec3 v_TexCoord;
            in vec4 v_Color;
            uniform sampler2DArray u_Textures;
            uniform float u_AlphaCutoff;
            out vec4 o_Color;
            void main() {
                vec4 tex = texture(u_Textures, v_TexCoord) * v_Color;
                if (tex.a < u_AlphaCutoff) discard;
                o_Color = tex;
            }
            """;
}
