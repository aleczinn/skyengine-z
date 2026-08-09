package de.skyengine.graphics.world;

import de.skyengine.core.SkyEngine;
import de.skyengine.game.world.environment.EnvironmentProfile;
import de.skyengine.graphics.FrameProfiler;
import de.skyengine.graphics.GlDebug;
import de.skyengine.graphics.camera.Camera;
import de.skyengine.graphics.shader.Shader;
import de.skyengine.graphics.shader.ShaderProgram;
import de.skyengine.graphics.shader.ShaderType;
import de.skyengine.graphics.shaderpack.ShaderPack;
import de.skyengine.graphics.shaderpack.ShaderPackManager;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL32;
import org.lwjgl.opengl.GL42;
import org.lwjgl.stb.STBImage;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;

/** Pack-driven HDR sky. Pack resources are prepared completely before an atomic swap. */
public final class SkyRenderer implements ShaderPackManager.Participant {
    private static final int SCATTERING_UNIT = 7;
    private static final int NOISE_UNIT = 8;
    private static final int FOG_CUBE_SIZE = 64;
    private static final Vector3f[] FOG_DIRECTIONS = {
            new Vector3f(1, 0, 0), new Vector3f(-1, 0, 0),
            new Vector3f(0, 1, 0), new Vector3f(0, -1, 0),
            new Vector3f(0, 0, 1), new Vector3f(0, 0, -1)
    };
    private static final Vector3f[] FOG_UP = {
            new Vector3f(0, -1, 0), new Vector3f(0, -1, 0),
            new Vector3f(0, 0, 1), new Vector3f(0, 0, -1),
            new Vector3f(0, -1, 0), new Vector3f(0, -1, 0)
    };
    private final EnvironmentProfile profile;
    private ShaderPackManager manager;
    private Resources resources;
    private int vao;
    private int fogCube;
    private int fogFramebuffer;

    public SkyRenderer(EnvironmentProfile profile) {
        this.profile = profile;
    }

    public void init() {
        this.manager = SkyEngine.get().getShaderPackManager();
        this.activate(this.prepare(this.manager.active()));
        this.manager.register(this);
        this.vao = GL30.glGenVertexArrays();
        /* glGenVertexArrays only reserves a name. The object is created by the first bind;
           labeling the reserved name before that is GL_INVALID_VALUE on NVIDIA. */
        GL30.glBindVertexArray(this.vao);
        GlDebug.labelVertexArray(this.vao, "Shader-pack sky VAO");
        GL30.glBindVertexArray(0);
        this.createFogCube();
    }

    @Override
    public ShaderPackManager.Prepared prepare(ShaderPack pack) {
        ShaderProgram program = null;
        int scattering = 0;
        int noise = 0;
        try {
            program = new ShaderProgram(
                    new Shader(pack.program("sky_vertex"), ShaderType.VERTEX),
                    new Shader(pack.program("sky_fragment"), ShaderType.FRAGMENT));
            scattering = loadScattering(pack);
            noise = loadNoise(pack);
            program.bind();
            program.setUniformi("u_AtmosphereScattering", SCATTERING_UNIT);
            program.setUniformi("u_Noise", NOISE_UNIT);
            program.setUniformf("u_SunIntensity", this.profile.sunIntensity());
            program.setUniformf("u_MoonIntensity", this.profile.moonIntensity());
            program.setUniformf("u_FogCapture", 0F);
            program.unbind();
            return new Resources(program, scattering, noise);
        } catch (RuntimeException e) {
            if (program != null) program.dispose();
            if (scattering != 0) GL11.glDeleteTextures(scattering);
            if (noise != 0) GL11.glDeleteTextures(noise);
            throw e;
        }
    }

    @Override
    public void activate(ShaderPackManager.Prepared prepared) {
        Resources next = (Resources) prepared;
        Resources previous = this.resources;
        this.resources = next;
        if (previous != null) previous.dispose();
    }

    public void render(Camera camera, float dayFraction) {
        Resources current = this.resources;
        if (current == null) return;
        boolean inverseDepth = SkyEngine.get().getWindow().getProperties().isUseInverseDepth();
        int previousDepthFunc = GL11.glGetInteger(GL11.GL_DEPTH_FUNC);
        current.program.bind();
        current.program.setUniformMatrix4f("u_InvProjectionView", camera.getInvProjectionViewMatrix());
        current.program.setUniformf("u_FarDepth", inverseDepth ? 0F : 1F);
        current.program.setUniformf("u_DayFraction", dayFraction);
        current.program.setUniformf("u_FogCapture", 0F);
        GL13.glActiveTexture(GL13.GL_TEXTURE0 + SCATTERING_UNIT);
        GL11.glBindTexture(GL12.GL_TEXTURE_3D, current.scattering);
        GL13.glActiveTexture(GL13.GL_TEXTURE0 + NOISE_UNIT);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, current.noise);
        GL11.glDepthMask(false);
        GL11.glDepthFunc(inverseDepth ? GL11.GL_GEQUAL : GL11.GL_LEQUAL);
        GL30.glBindVertexArray(this.vao);
        FrameProfiler.gpuBegin(FrameProfiler.Gpu.SKY);
        GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
        FrameProfiler.gpuEnd(FrameProfiler.Gpu.SKY);
        GL30.glBindVertexArray(0);
        GL11.glDepthFunc(previousDepthFunc);
        GL11.glDepthMask(true);
        current.program.unbind();
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
    }

    /**
     * Renders a tiny direction lookup from the active pack's actual atmosphere shader.
     * Terrain then needs one cubemap lookup instead of evaluating the Photon atmosphere
     * per fragment. Celestial discs and stars are excluded deliberately: this texture is
     * aerial perspective, not a reflection of the visible sky.
     */
    public void updateFogCube(float dayFraction) {
        Resources current = this.resources;
        if (current == null || this.fogCube == 0) return;
        int previousFramebuffer = GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING);
        boolean depthTest = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
        boolean blend = GL11.glIsEnabled(GL11.GL_BLEND);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer viewport = stack.mallocInt(4);
            GL11.glGetIntegerv(GL11.GL_VIEWPORT, viewport);

            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, this.fogFramebuffer);
            GL11.glViewport(0, 0, FOG_CUBE_SIZE, FOG_CUBE_SIZE);
            GL11.glDisable(GL11.GL_DEPTH_TEST);
            GL11.glDisable(GL11.GL_BLEND);
            current.program.bind();
            current.program.setUniformf("u_FarDepth", 1F);
            current.program.setUniformf("u_DayFraction", dayFraction);
            current.program.setUniformf("u_FogCapture", 1F);
            GL13.glActiveTexture(GL13.GL_TEXTURE0 + SCATTERING_UNIT);
            GL11.glBindTexture(GL12.GL_TEXTURE_3D, current.scattering);
            GL13.glActiveTexture(GL13.GL_TEXTURE0 + NOISE_UNIT);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, current.noise);
            GL30.glBindVertexArray(this.vao);

            for (int face = 0; face < 6; face++) {
                GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0,
                        GL13.GL_TEXTURE_CUBE_MAP_POSITIVE_X + face, this.fogCube, 0);
                Matrix4f projectionView = new Matrix4f()
                        .perspective((float) Math.PI * 0.5F, 1F, 0.1F, 10F)
                        .lookAt(new Vector3f(), FOG_DIRECTIONS[face], FOG_UP[face]);
                current.program.setUniformMatrix4f("u_InvProjectionView", projectionView.invert());
                GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
            }

            GL30.glBindVertexArray(0);
            current.program.setUniformf("u_FogCapture", 0F);
            current.program.unbind();
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, previousFramebuffer);
            GL11.glViewport(viewport.get(0), viewport.get(1), viewport.get(2), viewport.get(3));
        }
        if (depthTest) GL11.glEnable(GL11.GL_DEPTH_TEST); else GL11.glDisable(GL11.GL_DEPTH_TEST);
        if (blend) GL11.glEnable(GL11.GL_BLEND); else GL11.glDisable(GL11.GL_BLEND);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
    }

    public int fogTexture() {
        return this.fogCube;
    }

    private void createFogCube() {
        GL11.glEnable(GL32.GL_TEXTURE_CUBE_MAP_SEAMLESS);
        this.fogCube = GL11.glGenTextures();
        GL11.glBindTexture(GL13.GL_TEXTURE_CUBE_MAP, this.fogCube);
        for (int face = 0; face < 6; face++) {
            GL11.glTexImage2D(GL13.GL_TEXTURE_CUBE_MAP_POSITIVE_X + face, 0, GL30.GL_RGB16F,
                    FOG_CUBE_SIZE, FOG_CUBE_SIZE, 0, GL11.GL_RGB, GL11.GL_FLOAT, 0L);
        }
        GL11.glTexParameteri(GL13.GL_TEXTURE_CUBE_MAP, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL13.GL_TEXTURE_CUBE_MAP, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL13.GL_TEXTURE_CUBE_MAP, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL13.GL_TEXTURE_CUBE_MAP, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL13.GL_TEXTURE_CUBE_MAP, GL12.GL_TEXTURE_WRAP_R, GL12.GL_CLAMP_TO_EDGE);
        GL11.glBindTexture(GL13.GL_TEXTURE_CUBE_MAP, 0);
        this.fogFramebuffer = GL30.glGenFramebuffers();
        GlDebug.labelTexture(this.fogCube, "Shader-pack atmosphere fog cubemap");
    }

    private static int loadScattering(ShaderPack pack) {
        byte[] bytes;
        try (InputStream input = pack.texture("atmosphere_scattering")) {
            bytes = input.readAllBytes();
        } catch (IOException e) {
            throw new IllegalArgumentException("Cannot load Photon atmosphere LUT", e);
        }
        if (bytes.length != 32 * 64 * 32 * 3 * 2) {
            throw new IllegalArgumentException("Atmosphere LUT must be a 32x64x32 RGB16F volume");
        }
        ByteBuffer data = MemoryUtil.memAlloc(bytes.length);
        data.put(bytes).flip();
        int texture = GL11.glGenTextures();
        GL11.glBindTexture(GL12.GL_TEXTURE_3D, texture);
        GL42.glTexStorage3D(GL12.GL_TEXTURE_3D, 1, GL30.GL_RGB16F, 32, 64, 32);
        GL12.glTexSubImage3D(GL12.GL_TEXTURE_3D, 0, 0, 0, 0, 32, 64, 32,
                GL11.GL_RGB, GL30.GL_HALF_FLOAT, data);
        GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL12.GL_TEXTURE_WRAP_R, GL12.GL_CLAMP_TO_EDGE);
        GL11.glBindTexture(GL12.GL_TEXTURE_3D, 0);
        MemoryUtil.memFree(data);
        GlDebug.labelTexture(texture, "Photon atmosphere scattering LUT");
        return texture;
    }

    private static int loadNoise(ShaderPack pack) {
        byte[] bytes;
        try (InputStream input = pack.texture("moon_noise")) {
            bytes = input.readAllBytes();
        } catch (IOException e) {
            throw new IllegalArgumentException("Cannot load Photon moon noise", e);
        }
        ByteBuffer encoded = MemoryUtil.memAlloc(bytes.length);
        encoded.put(bytes).flip();
        ByteBuffer pixels;
        int width;
        int height;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer w = stack.mallocInt(1);
            IntBuffer h = stack.mallocInt(1);
            IntBuffer channels = stack.mallocInt(1);
            pixels = STBImage.stbi_load_from_memory(encoded, w, h, channels, 4);
            if (pixels == null) throw new IllegalArgumentException("Invalid moon noise: " + STBImage.stbi_failure_reason());
            width = w.get(0);
            height = h.get(0);
        } finally {
            MemoryUtil.memFree(encoded);
        }
        int texture = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, width, height, 0,
                GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixels);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL11.GL_REPEAT);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL11.GL_REPEAT);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
        STBImage.stbi_image_free(pixels);
        GlDebug.labelTexture(texture, "Photon moon noise");
        return texture;
    }

    public void dispose() {
        if (this.manager != null) this.manager.unregister(this);
        if (this.resources != null) this.resources.dispose();
        if (this.vao != 0) GL30.glDeleteVertexArrays(this.vao);
        if (this.fogFramebuffer != 0) GL30.glDeleteFramebuffers(this.fogFramebuffer);
        if (this.fogCube != 0) GL11.glDeleteTextures(this.fogCube);
        this.resources = null;
        this.vao = 0;
        this.fogFramebuffer = 0;
        this.fogCube = 0;
    }

    private static final class Resources implements ShaderPackManager.Prepared {
        private final ShaderProgram program;
        private final int scattering;
        private final int noise;

        private Resources(ShaderProgram program, int scattering, int noise) {
            this.program = program;
            this.scattering = scattering;
            this.noise = noise;
        }

        @Override
        public void dispose() {
            this.program.dispose();
            GL11.glDeleteTextures(this.scattering);
            GL11.glDeleteTextures(this.noise);
        }
    }
}
