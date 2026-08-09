package de.skyengine.graphics.post.passes;

import de.skyengine.core.SkyEngine;
import de.skyengine.graphics.post.PostContext;
import de.skyengine.graphics.post.PostPass;
import de.skyengine.graphics.post.PostProcessor;
import de.skyengine.graphics.shader.Shader;
import de.skyengine.graphics.shader.ShaderProgram;
import de.skyengine.graphics.shader.ShaderType;
import de.skyengine.graphics.shaderpack.ShaderPack;
import de.skyengine.graphics.shaderpack.ShaderPackManager;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL30;

import java.nio.ByteBuffer;

/** Photon's threshold-free six-level HDR bloom, exposed as a hot-reloadable pack pass. */
public final class BloomPass implements PostPass, ShaderPackManager.Participant {
    private static final int LEVELS = 6;
    private final int[][] textures = new int[2][LEVELS];
    private final int[][] framebuffers = new int[2][LEVELS];
    private final int[] widths = new int[LEVELS];
    private final int[] heights = new int[LEVELS];
    private ShaderPackManager manager;
    private Programs programs;

    @Override
    public void init(PostContext context) {
        this.manager = SkyEngine.get().getShaderPackManager();
        this.activate(this.prepare(this.manager.active()));
        this.manager.register(this);
        this.createTargets(context.width, context.height);
    }

    @Override
    public ShaderPackManager.Prepared prepare(ShaderPack pack) {
        ShaderProgram downsample = null;
        ShaderProgram blur = null;
        ShaderProgram upsample = null;
        ShaderProgram composite = null;
        Programs result;
        try {
            downsample = compile(pack.program("bloom_downsample"));
            blur = compile(pack.program("bloom_blur"));
            upsample = compile(pack.program("bloom_upsample"));
            composite = compile(pack.program("bloom_composite"));
            result = new Programs(downsample, blur, upsample, composite);
        } catch (RuntimeException e) {
            if (downsample != null) downsample.dispose();
            if (blur != null) blur.dispose();
            if (upsample != null) upsample.dispose();
            if (composite != null) composite.dispose();
            throw e;
        }
        result.downsample.bind(); result.downsample.setUniformi("u_Input", 0); result.downsample.unbind();
        result.blur.bind(); result.blur.setUniformi("u_Input", 0); result.blur.unbind();
        result.upsample.bind();
        result.upsample.setUniformi("u_Low", 0); result.upsample.setUniformi("u_Source", 1);
        result.upsample.unbind();
        result.composite.bind();
        result.composite.setUniformi("u_Scene", 0); result.composite.setUniformi("u_Bloom", 1);
        result.composite.unbind();
        return result;
    }

    private static ShaderProgram compile(String fragment) {
        return new ShaderProgram(new Shader(PostProcessor.FULLSCREEN_VERTEX_SHADER, ShaderType.VERTEX),
                new Shader(fragment, ShaderType.FRAGMENT));
    }

    @Override
    public void activate(ShaderPackManager.Prepared prepared) {
        Programs previous = this.programs;
        this.programs = (Programs) prepared;
        if (previous != null) previous.dispose();
    }

    @Override
    public void resize(PostContext context) {
        this.disposeTargets();
        this.createTargets(context.width, context.height);
    }

    private void createTargets(int width, int height) {
        for (int level = 0; level < LEVELS; level++) {
            width = Math.max(1, width / 2);
            height = Math.max(1, height / 2);
            this.widths[level] = width;
            this.heights[level] = height;
            for (int set = 0; set < 2; set++) {
                this.textures[set][level] = GL11.glGenTextures();
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, this.textures[set][level]);
                GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_RGBA16F, width, height, 0,
                        GL11.GL_RGBA, GL11.GL_FLOAT, (ByteBuffer) null);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
                this.framebuffers[set][level] = GL30.glGenFramebuffers();
                GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, this.framebuffers[set][level]);
                GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0,
                        GL11.GL_TEXTURE_2D, this.textures[set][level], 0);
                if (GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER) != GL30.GL_FRAMEBUFFER_COMPLETE) {
                    throw new IllegalStateException("Bloom framebuffer is incomplete");
                }
            }
        }
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
    }

    @Override
    public boolean isActive(PostContext context) {
        return context.settings.getBloomIntensity() > 0F;
    }

    @Override
    public void execute(PostContext context) {
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        int source = context.input;
        int sourceWidth = context.width;
        int sourceHeight = context.height;
        this.programs.downsample.bind();
        for (int level = 0; level < LEVELS; level++) {
            drawTo(this.framebuffers[0][level], this.widths[level], this.heights[level]);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, source);
            this.programs.downsample.setUniformVector2f("u_TexelSize", 1F/sourceWidth, 1F/sourceHeight);
            context.drawFullscreenTriangle();
            source = this.textures[0][level];
            sourceWidth = this.widths[level];
            sourceHeight = this.heights[level];
        }
        this.programs.downsample.unbind();

        this.programs.blur.bind();
        for (int level = 0; level < LEVELS; level++) {
            drawTo(this.framebuffers[1][level], this.widths[level], this.heights[level]);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, this.textures[0][level]);
            this.programs.blur.setUniformVector2f("u_Direction", 1F/this.widths[level], 0F);
            context.drawFullscreenTriangle();
            drawTo(this.framebuffers[0][level], this.widths[level], this.heights[level]);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, this.textures[1][level]);
            this.programs.blur.setUniformVector2f("u_Direction", 0F, 1F/this.heights[level]);
            context.drawFullscreenTriangle();
        }
        this.programs.blur.unbind();

        int accumulated = this.textures[0][LEVELS - 1];
        this.programs.upsample.bind();
        for (int level = LEVELS - 2; level >= 0; level--) {
            drawTo(this.framebuffers[1][level], this.widths[level], this.heights[level]);
            GL13.glActiveTexture(GL13.GL_TEXTURE0); GL11.glBindTexture(GL11.GL_TEXTURE_2D, accumulated);
            GL13.glActiveTexture(GL13.GL_TEXTURE1); GL11.glBindTexture(GL11.GL_TEXTURE_2D, this.textures[0][level]);
            context.drawFullscreenTriangle();
            accumulated = this.textures[1][level];
        }
        this.programs.upsample.unbind();

        drawTo(context.targetFbo, context.width, context.height);
        this.programs.composite.bind();
        this.programs.composite.setUniformf("u_Intensity", context.settings.getBloomIntensity());
        GL13.glActiveTexture(GL13.GL_TEXTURE0); GL11.glBindTexture(GL11.GL_TEXTURE_2D, context.input);
        GL13.glActiveTexture(GL13.GL_TEXTURE1); GL11.glBindTexture(GL11.GL_TEXTURE_2D, accumulated);
        context.drawFullscreenTriangle();
        this.programs.composite.unbind();
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
    }

    private static void drawTo(int fbo, int width, int height) {
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
        GL11.glViewport(0, 0, width, height);
    }

    private void disposeTargets() {
        for (int set = 0; set < 2; set++) for (int level = 0; level < LEVELS; level++) {
            if (this.framebuffers[set][level] != 0) GL30.glDeleteFramebuffers(this.framebuffers[set][level]);
            if (this.textures[set][level] != 0) GL11.glDeleteTextures(this.textures[set][level]);
            this.framebuffers[set][level] = 0;
            this.textures[set][level] = 0;
        }
    }

    @Override
    public void dispose() {
        if (this.manager != null) this.manager.unregister(this);
        this.disposeTargets();
        if (this.programs != null) this.programs.dispose();
    }

    private record Programs(ShaderProgram downsample, ShaderProgram blur, ShaderProgram upsample,
                            ShaderProgram composite) implements ShaderPackManager.Prepared {
        @Override public void dispose() {
            downsample.dispose(); blur.dispose(); upsample.dispose(); composite.dispose();
        }
    }
}
