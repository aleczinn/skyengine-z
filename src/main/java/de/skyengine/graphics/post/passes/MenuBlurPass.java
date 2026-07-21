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
 * Menü-Hintergrund-Blur (MC-1.20.5-Stil): Solange ein Screen mit {@code blursBackground()}
 * offen ist, wird die fertige Szene weichgezeichnet — separabler 9-Tap-Gauss in ¼-Auflösung
 * (Downsample steckt im ersten Blur-Schritt, bilineares Sampling), dann Composite
 * {@code mix(scharf, geblurt, strength)}. Die Stärke animiert zeitbasiert 0→1 beim Öffnen
 * und 1→0 beim Schließen (~180 ms) — der Mix ist zugleich die Einblend-Animation.
 *
 * <p>Als LETZTER Pass der Kette registriert: bei Stärke 0 inaktiv (voriger Pass schreibt wie
 * bisher in FBO 0), sonst übernimmt er automatisch das Default-FBO. Liest nur das Ergebnis
 * der Kette — kann konstruktionsbedingt nicht in die TAA-History zurückfüttern.
 */
public final class MenuBlurPass implements PostPass {

    /** ¼-Auflösung: weich genug für Menü-Hintergrund, praktisch gratis. */
    private static final int DOWNSCALE = 4;
    /** Ein-/Ausblendzeit der Blur-Stärke in Sekunden. */
    private static final float FADE_SECONDS = 0.08f;

    private static final String BLUR_FRAGMENT = """
            #version 460 core
            in vec2 v_uv;
            out vec4 fragColor;
            uniform sampler2D u_Input;
            uniform vec2 u_Direction; // (1/w, 0) bzw. (0, 1/h) der ZIEL-Aufloesung

            /* 9-Tap-Gauss (sigma ~2), symmetrisch um das Zentrum */
            const float W0 = 0.227027;
            const float W[4] = float[](0.194595, 0.121622, 0.054054, 0.016216);

            void main() {
                vec3 c = texture(u_Input, v_uv).rgb * W0;
                for (int i = 0; i < 4; i++) {
                    vec2 off = u_Direction * float(i + 1);
                    c += texture(u_Input, v_uv + off).rgb * W[i];
                    c += texture(u_Input, v_uv - off).rgb * W[i];
                }
                fragColor = vec4(c, 1.0);
            }
            """;

    private static final String COMPOSITE_FRAGMENT = """
            #version 460 core
            in vec2 v_uv;
            out vec4 fragColor;
            uniform sampler2D u_Scene;   // scharfes Ketten-Ergebnis (voll aufgeloest)
            uniform sampler2D u_Blurred; // geblurte Kleinversion (bilinear hochgesampelt)
            uniform float u_Strength;    // 0..1 (animierter Fade)

            void main() {
                vec3 sharp = texture(u_Scene, v_uv).rgb;
                vec3 blurred = texture(u_Blurred, v_uv).rgb;
                fragColor = vec4(mix(sharp, blurred, u_Strength), 1.0);
            }
            """;

    private ShaderProgram blurProgram;
    private ShaderProgram compositeProgram;

    private final int[] smallFbo = new int[2];
    private final int[] smallTex = new int[2];
    private int smallW, smallH;

    /* Animation: Zielzustand kommt 1x/Frame via setTarget, Staerke laeuft zeitbasiert nach. */
    private boolean target;
    private float strength;
    private long lastNanos = System.nanoTime();

    /** 1×/Frame (GameContainer): Blur gewünscht? Treibt die zeitbasierte Ein-/Ausblendung. */
    public void setTarget(boolean active) {
        this.target = active;
        long now = System.nanoTime();
        float dt = Math.min((now - this.lastNanos) / 1.0e9f, 0.1f);
        this.lastNanos = now;
        float step = dt / FADE_SECONDS;
        this.strength = Math.clamp(this.strength + (active ? step : -step), 0f, 1f);
    }

    @Override
    public void init(PostContext context) {
        this.blurProgram = new ShaderProgram(
                new Shader(PostProcessor.FULLSCREEN_VERTEX_SHADER, ShaderType.VERTEX),
                new Shader(BLUR_FRAGMENT, ShaderType.FRAGMENT));
        this.blurProgram.bind();
        this.blurProgram.setUniformi("u_Input", 0);
        this.blurProgram.unbind();

        this.compositeProgram = new ShaderProgram(
                new Shader(PostProcessor.FULLSCREEN_VERTEX_SHADER, ShaderType.VERTEX),
                new Shader(COMPOSITE_FRAGMENT, ShaderType.FRAGMENT));
        this.compositeProgram.bind();
        this.compositeProgram.setUniformi("u_Scene", 0);
        this.compositeProgram.setUniformi("u_Blurred", 1);
        this.compositeProgram.unbind();

        this.createTargets(context.width, context.height);
    }

    @Override
    public void resize(PostContext context) {
        this.disposeTargets();
        this.createTargets(context.width, context.height);
    }

    private void createTargets(int width, int height) {
        this.smallW = Math.max(1, width / DOWNSCALE);
        this.smallH = Math.max(1, height / DOWNSCALE);
        for (int i = 0; i < 2; i++) {
            this.smallTex[i] = GL11.glGenTextures();
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, this.smallTex[i]);
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, this.smallW, this.smallH, 0,
                    GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);

            this.smallFbo[i] = GL30.glGenFramebuffers();
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, this.smallFbo[i]);
            GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0,
                    GL11.GL_TEXTURE_2D, this.smallTex[i], 0);
        }
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
    }

    @Override
    public boolean isActive(PostContext context) {
        return this.strength > 0f;
    }

    @Override
    public void execute(PostContext context) {
        /* 1) Downsample + H-Blur in einem: volle Eingangstextur bilinear in ¼-Res sampeln. */
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, this.smallFbo[0]);
        GL11.glViewport(0, 0, this.smallW, this.smallH);
        this.blurProgram.bind();
        this.blurProgram.setUniformVector2f("u_Direction", 1f / this.smallW, 0f);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, context.input);
        context.drawFullscreenTriangle();

        /* 2) V-Blur klein -> klein. */
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, this.smallFbo[1]);
        this.blurProgram.setUniformVector2f("u_Direction", 0f, 1f / this.smallH);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, this.smallTex[0]);
        context.drawFullscreenTriangle();
        this.blurProgram.unbind();

        /* 3) Composite in voller Aufloesung: mix(scharf, geblurt, strength). */
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, context.targetFbo);
        GL11.glViewport(0, 0, context.width, context.height);
        this.compositeProgram.bind();
        this.compositeProgram.setUniformf("u_Strength", this.strength);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, context.input);
        GL13.glActiveTexture(GL13.GL_TEXTURE1);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, this.smallTex[1]);
        context.drawFullscreenTriangle();
        this.compositeProgram.unbind();
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
    }

    private void disposeTargets() {
        for (int i = 0; i < 2; i++) {
            if (this.smallFbo[i] != 0) GL30.glDeleteFramebuffers(this.smallFbo[i]);
            if (this.smallTex[i] != 0) GL11.glDeleteTextures(this.smallTex[i]);
            this.smallFbo[i] = 0;
            this.smallTex[i] = 0;
        }
    }

    @Override
    public void dispose() {
        this.disposeTargets();
        if (this.blurProgram != null) this.blurProgram.dispose();
        if (this.compositeProgram != null) this.compositeProgram.dispose();
    }
}
