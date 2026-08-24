package de.skyengine.graphics.post.passes;

import de.skyengine.graphics.post.PostContext;
import de.skyengine.graphics.post.PostPass;
import de.skyengine.graphics.post.PostProcessor;
import de.skyengine.graphics.shader.Shader;
import de.skyengine.graphics.shader.ShaderProgram;
import de.skyengine.graphics.shader.ShaderType;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL30;

/** Violette Weltverzerrung, deren Staerke dem Kontaktfortschritt des Portals folgt. */
public final class PortalDistortionPass implements PostPass {

    private static final String FRAGMENT_SHADER = """
            #version 460 core
            in vec2 v_uv;
            out vec4 fragColor;
            uniform sampler2D u_Input;
            uniform float u_Progress;
            uniform float u_Time;

            void main() {
                vec2 centered = v_uv - 0.5;
                float radius = length(centered);
                float strength = smoothstep(0.0, 1.0, u_Progress);
                float wave = sin(radius * 31.0 - u_Time * 2.2)
                        + sin((v_uv.x + v_uv.y) * 15.0 + u_Time * 1.35) * 0.55;
                vec2 direction = radius > 0.0001 ? centered / radius : vec2(0.0);
                vec2 uv = clamp(v_uv + direction * wave * 0.012 * strength,
                        vec2(0.001), vec2(0.999));
                vec3 scene = texture(u_Input, uv).rgb;
                float wispA = sin(v_uv.y * 23.0 + sin(v_uv.x * 9.0 - u_Time) * 2.0 - u_Time * 1.4);
                float wispB = sin(v_uv.x * 17.0 - v_uv.y * 8.0 + u_Time * 1.1);
                float wisps = smoothstep(0.15, 0.95, wispA * 0.55 + wispB * 0.25 + 0.45);
                float edge = smoothstep(0.16, 0.70, radius);
                vec3 violet = vec3(0.28, 0.045, 0.46);
                vec3 lavender = vec3(0.49, 0.18, 0.69);
                scene = mix(scene, scene * 0.70 + violet * 0.58,
                        strength * (0.18 + edge * 0.43));
                scene += lavender * wisps * strength * 0.075;
                scene *= 1.0 - edge * strength * 0.18;
                fragColor = vec4(scene, 1.0);
            }
            """;

    private ShaderProgram program;
    private float progress;

    public void setProgress(float progress) {
        this.progress = Math.clamp(progress, 0F, 1F);
    }

    public float progress() {
        return this.progress;
    }

    @Override
    public void init(PostContext context) {
        this.program = new ShaderProgram(
                new Shader(PostProcessor.FULLSCREEN_VERTEX_SHADER, ShaderType.VERTEX),
                new Shader(FRAGMENT_SHADER, ShaderType.FRAGMENT));
        this.program.bind();
        this.program.setUniformi("u_Input", 0);
        this.program.unbind();
    }

    @Override public boolean isActive(PostContext context) { return this.progress > 0.001F; }

    @Override
    public void execute(PostContext context) {
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, context.targetFbo);
        this.program.bind();
        this.program.setUniformf("u_Progress", this.progress);
        this.program.setUniformf("u_Time", context.frame / 60F);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, context.input);
        context.drawFullscreenTriangle();
        this.program.unbind();
    }

    @Override public void dispose() { if (this.program != null) this.program.dispose(); }
}
