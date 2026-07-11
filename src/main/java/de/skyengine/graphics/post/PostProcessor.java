package de.skyengine.graphics.post;

import de.skyengine.core.io.IDisposable;
import de.skyengine.graphics.framebuffer.FrameBuffer;
import de.skyengine.graphics.post.passes.AntiAliasingPass;
import de.skyengine.graphics.post.passes.ColorGradingPass;
import de.skyengine.utils.logging.LogManager;
import de.skyengine.utils.logging.Logger;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL31;
import org.lwjgl.system.MemoryStack;

import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Besitzer der Post-Processing-Kette: hält {@link PostContext}, Settings-UBO und die
 * geordnete {@link PostPass}-Liste (Phase 1: ColorGrading → AntiAliasing). Verkettung:
 * Eingang des ersten Passes ist die aufgelöste Szene ({@code sceneColor}), inaktive Pässe
 * werden übersprungen, der letzte aktive Pass schreibt in den Default-Framebuffer, alle
 * davor in die LDR-Ping-Pong-Ziele des Contexts. Spätere Pässe (Bloom, AutoExposure,
 * LUT — nach Grading/vor AA —, SSR) werden nur in die Liste eingefügt.
 *
 * <p>Läuft NACH dem Welt-Pass und VOR der GUI — die GUI durchläuft die Kette nie
 * (HUD/Text bleiben pixelgenau).
 */
public class PostProcessor implements IDisposable {

    private final Logger logger = LogManager.getLogger(PostProcessor.class.getName());

    /**
     * UBO-Bindepunkt des Settings-Blocks (muss zu den layout(binding=...)-Deklarationen
     * der Pass-Shader passen).
     */
    public static final int SETTINGS_UBO_BINDING = 2;

    /**
     * Gemeinsamer Fullscreen-Triangle-Vertex-Shader aller Pässe (Positionen aus
     * gl_VertexID, kein VBO — Gegenstück: {@link PostContext#drawFullscreenTriangle}).
     */
    public static final String FULLSCREEN_VERTEX_SHADER = """
            #version 460 core
            
            out vec2 v_uv;
            
            void main() {
                vec2 pos = vec2((gl_VertexID << 1) & 2, gl_VertexID & 2);
                v_uv = pos;
                gl_Position = vec4(pos * 2.0 - 1.0, 0.0, 1.0);
            }
            """;

    /* UBO: 4x vec4 (gepackte Skalare) — Layout muss dem u_PostSettings-Block der Shader
       entsprechen (s. ColorGradingPass). */
    private static final int UBO_FLOATS = 16;

    private final PostContext context = new PostContext();
    private final List<PostPass> passes = new ArrayList<>();
    private PostProcessingSettings settings;
    private int ubo;

    /**
     * Render-Thread, GL-Kontext aktiv (nach FrameBuffer.create()).
     */
    public void init(int width, int height) {
        this.settings = PostProcessingSettings.load();

        this.ubo = GL15.glGenBuffers();
        GL15.glBindBuffer(GL31.GL_UNIFORM_BUFFER, this.ubo);
        GL15.glBufferData(GL31.GL_UNIFORM_BUFFER, UBO_FLOATS * Float.BYTES, GL15.GL_DYNAMIC_DRAW);
        GL30.glBindBufferBase(GL31.GL_UNIFORM_BUFFER, SETTINGS_UBO_BINDING, this.ubo);
        GL15.glBindBuffer(GL31.GL_UNIFORM_BUFFER, 0);

        this.context.settings = this.settings;
        this.context.create(width, height);

        this.passes.add(new ColorGradingPass());
        this.passes.add(new AntiAliasingPass());
        for (PostPass pass : this.passes) pass.init(this.context);

        this.logger.debug("PostProcessor: " + this.passes.size() + " Pässe, UBO-Binding " + SETTINGS_UBO_BINDING);
    }

    public PostProcessingSettings getSettings() {
        return this.settings;
    }

    public void resize(int width, int height) {
        this.context.resize(width, height);

        for (PostPass pass : this.passes) {
            pass.resize(this.context);
        }
    }

    /**
     * Führt die Kette aus: Szene ({@code frameBuffer}, bereits resolved) → Pässe → Screen.
     * Erwartet den Default-Framebuffer als Endziel; lässt Depth-Test/Blend deaktiviert
     * zurück (die GUI setzt ihren State selbst, SkyEngine reaktiviert pro Frame).
     */
    public void render(FrameBuffer frameBuffer) {
        this.context.sceneColor = frameBuffer.getColorTexture();
        this.context.sceneDepth = frameBuffer.getDepthTexture();

        if (this.settings.consumeDirty()) this.uploadUbo();

        /* Fullscreen-Pässe: kein Depth (Default-FB-Depth ist unangetastet/Reversed-Z-clear,
           GREATER würde das Dreieck bei z=0 verwerfen), kein Blend. */
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glDisable(GL11.GL_BLEND);

        int input = this.context.sceneColor;
        int ping = 0;
        PostPass last = null;
        for (PostPass pass : this.passes) {
            if (pass.isActive(this.context)) last = pass;
        }
        for (PostPass pass : this.passes) {
            if (!pass.isActive(this.context)) continue;
            boolean isLast = pass == last;
            this.context.input = input;
            this.context.targetFbo = isLast ? 0 : this.context.pingFbo(ping);
            pass.execute(this.context);
            if (!isLast) {
                input = this.context.pingTexture(ping);
                ping = 1 - ping;
            }
        }
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
    }

    /**
     * Packt die Settings ins UBO — Reihenfolge spiegelt den u_PostSettings-Block.
     */
    private void uploadUbo() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            FloatBuffer buf = stack.mallocFloat(UBO_FLOATS);
            buf.put(this.settings.getExposure()).put(this.settings.getGamma())
                    .put(this.settings.getContrast()).put(this.settings.getSaturation());
            buf.put(this.settings.getVibrance()).put(this.settings.getBrightness())
                    .put(this.settings.getTemperature()).put(this.settings.getTint());
            buf.put(this.settings.getLift()).put(this.settings.getGain())
                    .put(this.settings.getShadows()).put(this.settings.getHighlights());
            buf.put(this.settings.getMidtones()).put(this.settings.getTonemapOperator().ordinal())
                    .put(this.settings.getDebugMode().ordinal()).put(0F);
            buf.flip();
            GL15.glBindBuffer(GL31.GL_UNIFORM_BUFFER, this.ubo);
            GL15.glBufferSubData(GL31.GL_UNIFORM_BUFFER, 0, buf);
            GL15.glBindBuffer(GL31.GL_UNIFORM_BUFFER, 0);
        }
    }

    @Override
    public void dispose() {
        for (PostPass pass : this.passes) pass.dispose();
        this.passes.clear();
        this.context.dispose();
        if (this.ubo != 0) {
            GL15.glDeleteBuffers(this.ubo);
            this.ubo = 0;
        }
    }
}
