package de.skyengine.graphics.post.passes;

import de.skyengine.graphics.post.PostContext;
import de.skyengine.graphics.post.PostPass;
import de.skyengine.graphics.post.PostProcessor;
import de.skyengine.graphics.shader.Shader;
import de.skyengine.graphics.shader.ShaderProgram;
import de.skyengine.graphics.shader.ShaderType;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL30;

import java.nio.ByteBuffer;

/**
 * Multi-resolution HDR bloom. Six progressively smaller RGBA16F images provide both the
 * tight glow around a light source and the broad atmospheric veil visible around the moon.
 * The pass deliberately runs before color grading so values above display white survive
 * long enough to generate bloom.
 */
public final class BloomPass implements PostPass {

    private static final int LEVELS = 6;

    private static final String DOWNSAMPLE_FRAGMENT = """
            #version 460 core
            in vec2 v_uv;
            out vec4 fragColor;
            uniform sampler2D u_Input;
            uniform vec2 u_TexelSize;
            uniform float u_Threshold;
            uniform bool u_Prefilter;

            void main() {
                vec2 d = u_TexelSize * 1.5;
                vec3 c = texture(u_Input, v_uv).rgb * 4.0;
                c += texture(u_Input, v_uv + vec2( d.x,  d.y)).rgb;
                c += texture(u_Input, v_uv + vec2(-d.x,  d.y)).rgb;
                c += texture(u_Input, v_uv + vec2( d.x, -d.y)).rgb;
                c += texture(u_Input, v_uv + vec2(-d.x, -d.y)).rgb;
                c += texture(u_Input, v_uv + vec2(2.0 * d.x, 0.0)).rgb;
                c += texture(u_Input, v_uv - vec2(2.0 * d.x, 0.0)).rgb;
                c += texture(u_Input, v_uv + vec2(0.0, 2.0 * d.y)).rgb;
                c += texture(u_Input, v_uv - vec2(0.0, 2.0 * d.y)).rgb;
                c /= 12.0;

                if (u_Prefilter) {
                    float brightness = max(c.r, max(c.g, c.b));
                    float knee = max(u_Threshold * 0.45, 0.0001);
                    float soft = clamp((brightness - u_Threshold + knee) / (2.0 * knee), 0.0, 1.0);
                    float contribution = max(brightness - u_Threshold, 0.0) + soft * soft * knee;
                    c *= contribution / max(brightness, 0.0001);
                }
                fragColor = vec4(c, 1.0);
            }
            """;

    private static final String COMPOSITE_FRAGMENT = """
            #version 460 core
            in vec2 v_uv;
            out vec4 fragColor;
            uniform sampler2D u_Scene;
            uniform sampler2D u_Bloom0;
            uniform sampler2D u_Bloom1;
            uniform sampler2D u_Bloom2;
            uniform sampler2D u_Bloom3;
            uniform sampler2D u_Bloom4;
            uniform sampler2D u_Bloom5;
            uniform float u_Intensity;

            void main() {
                vec3 bloom = texture(u_Bloom0, v_uv).rgb * 0.18
                        + texture(u_Bloom1, v_uv).rgb * 0.18
                        + texture(u_Bloom2, v_uv).rgb * 0.17
                        + texture(u_Bloom3, v_uv).rgb * 0.17
                        + texture(u_Bloom4, v_uv).rgb * 0.16
                        + texture(u_Bloom5, v_uv).rgb * 0.14;
                fragColor = vec4(texture(u_Scene, v_uv).rgb + bloom * u_Intensity, 1.0);
            }
            """;

    private ShaderProgram downsampleProgram;
    private ShaderProgram compositeProgram;
    private final int[] textures = new int[LEVELS];
    private final int[] framebuffers = new int[LEVELS];
    private final int[] widths = new int[LEVELS];
    private final int[] heights = new int[LEVELS];

    @Override
    public void init(PostContext context) {
        this.downsampleProgram = new ShaderProgram(
                new Shader(PostProcessor.FULLSCREEN_VERTEX_SHADER, ShaderType.VERTEX),
                new Shader(DOWNSAMPLE_FRAGMENT, ShaderType.FRAGMENT));
        this.downsampleProgram.bind();
        this.downsampleProgram.setUniformi("u_Input", 0);
        this.downsampleProgram.unbind();

        this.compositeProgram = new ShaderProgram(
                new Shader(PostProcessor.FULLSCREEN_VERTEX_SHADER, ShaderType.VERTEX),
                new Shader(COMPOSITE_FRAGMENT, ShaderType.FRAGMENT));
        this.compositeProgram.bind();
        this.compositeProgram.setUniformi("u_Scene", 0);
        for (int i = 0; i < LEVELS; i++) {
            this.compositeProgram.setUniformi("u_Bloom" + i, i + 1);
        }
        this.compositeProgram.unbind();
        this.createTargets(context.width, context.height);
    }

    @Override
    public void resize(PostContext context) {
        this.disposeTargets();
        this.createTargets(context.width, context.height);
    }

    private void createTargets(int width, int height) {
        for (int i = 0; i < LEVELS; i++) {
            width = Math.max(1, width / 2);
            height = Math.max(1, height / 2);
            this.widths[i] = width;
            this.heights[i] = height;

            this.textures[i] = GL11.glGenTextures();
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, this.textures[i]);
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_RGBA16F, width, height, 0,
                    GL11.GL_RGBA, GL11.GL_FLOAT, (ByteBuffer) null);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);

            this.framebuffers[i] = GL30.glGenFramebuffers();
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, this.framebuffers[i]);
            GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0,
                    GL11.GL_TEXTURE_2D, this.textures[i], 0);
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
        this.downsampleProgram.bind();
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        int source = context.input;
        int sourceWidth = context.width;
        int sourceHeight = context.height;
        for (int i = 0; i < LEVELS; i++) {
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, this.framebuffers[i]);
            GL11.glViewport(0, 0, this.widths[i], this.heights[i]);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, source);
            this.downsampleProgram.setUniformVector2f("u_TexelSize",
                    1F / sourceWidth, 1F / sourceHeight);
            this.downsampleProgram.setUniformf("u_Threshold", context.settings.getBloomThreshold());
            this.downsampleProgram.setUniformi("u_Prefilter", i == 0 ? 1 : 0);
            context.drawFullscreenTriangle();
            source = this.textures[i];
            sourceWidth = this.widths[i];
            sourceHeight = this.heights[i];
        }
        this.downsampleProgram.unbind();

        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, context.targetFbo);
        GL11.glViewport(0, 0, context.width, context.height);
        this.compositeProgram.bind();
        this.compositeProgram.setUniformf("u_Intensity", context.settings.getBloomIntensity());
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, context.input);
        for (int i = 0; i < LEVELS; i++) {
            GL13.glActiveTexture(GL13.GL_TEXTURE1 + i);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, this.textures[i]);
        }
        context.drawFullscreenTriangle();
        this.compositeProgram.unbind();
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
    }

    private void disposeTargets() {
        for (int i = 0; i < LEVELS; i++) {
            if (this.framebuffers[i] != 0) GL30.glDeleteFramebuffers(this.framebuffers[i]);
            if (this.textures[i] != 0) GL11.glDeleteTextures(this.textures[i]);
            this.framebuffers[i] = 0;
            this.textures[i] = 0;
        }
    }

    @Override
    public void dispose() {
        this.disposeTargets();
        if (this.downsampleProgram != null) this.downsampleProgram.dispose();
        if (this.compositeProgram != null) this.compositeProgram.dispose();
    }
}
