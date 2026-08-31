package de.skyengine.graphics.post;

import de.skyengine.core.io.IDisposable;
import de.skyengine.graphics.camera.Camera;
import de.skyengine.graphics.FrameProfiler;
import de.skyengine.graphics.framebuffer.FrameBuffer;
import de.skyengine.graphics.post.PostProcessingSettings.AntiAliasingMode;
import de.skyengine.graphics.post.passes.AntiAliasingPass;
import de.skyengine.graphics.post.passes.ColorGradingPass;
import de.skyengine.graphics.post.passes.MenuBlurPass;
import de.skyengine.graphics.post.passes.PortalDistortionPass;
import de.skyengine.graphics.post.passes.UnderwaterFogPass;
import org.joml.Vector2f;
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
 * geordnete {@link PostPass}-Liste (ColorGrading → UnderwaterFog → AntiAliasing). Verkettung:
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
    private final MenuBlurPass menuBlur = new MenuBlurPass();
    private final UnderwaterFogPass underwaterFog = new UnderwaterFogPass();
    private final PortalDistortionPass portalDistortion = new PortalDistortionPass();
    private final AntiAliasingPass antiAliasing = new AntiAliasingPass();
    private PostProcessingSettings settings;
    private int ubo;

    /* TAA-Jitter: Halton(2,3)-Index, advanct nur bei aktivem TAA (nextJitter). */
    private int jitterFrame;

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
        this.context.reversedDepth = de.skyengine.core.SkyEngine.get().getWindow()
                .getProperties().isUseInverseDepth();
        this.context.create(width, height);

        this.passes.add(new ColorGradingPass());
        this.passes.add(this.underwaterFog);
        this.passes.add(this.portalDistortion);
        this.passes.add(this.antiAliasing);
        /* Menü-Blur als LETZTER Pass: nur aktiv bei offenem Pause-Menü (Stärke > 0) —
           dann übernimmt er automatisch das Default-FBO (Last-Active-Mechanik der Kette). */
        this.passes.add(this.menuBlur);
        for (PostPass pass : this.passes) pass.init(this.context);

        this.logger.debug("PostProcessor: " + this.passes.size() + " Pässe, UBO-Binding " + SETTINGS_UBO_BINDING);
    }

    public PostProcessingSettings getSettings() {
        return this.settings;
    }

    /** 1×/Frame (GameContainer): Menü-Blur an/aus — die Stärke blendet zeitbasiert nach. */
    public void setMenuBlur(boolean active) {
        this.menuBlur.setTarget(active);
    }

    /** Aktiviert den Wassernebel und übergibt Minecrafts tickbasierten Water-Vision-Faktor. */
    public void setUnderwater(boolean underwater, float waterVision) {
        this.context.waterVision = Math.clamp(waterVision, 0F, 1F);
        if (this.context.underwater == underwater) return;
        this.context.underwater = underwater;
        this.antiAliasing.invalidateHistory();
    }

    public void setPortalEffect(float progress) {
        boolean wasActive = this.portalDistortion.progress() > 0.001F;
        this.portalDistortion.setProgress(progress);
        if (wasActive != (this.portalDistortion.progress() > 0.001F)) {
            this.antiAliasing.invalidateHistory();
        }
    }

    /**
     * NDC-Subpixel-Jitter des Frames für {@link Camera#setJitter} — Halton(2,3)-Sequenz
     * (8 Samples, Pixel-Offset ±0,5 → NDC über die Fenstergröße). Liefert (0,0) und
     * advanct NICHT, wenn TAA nicht aktiv ist (kein Bild-Wackeln bei NONE/FXAA).
     */
    public void nextJitter(Vector2f dest, int width, int height) {
        if (!this.settings.isTemporalAa()) {
            dest.set(0F, 0F);
            this.context.jitterUv.set(0F, 0F);
            return;
        }
        this.jitterFrame = (this.jitterFrame + 1) & 7;
        int i = this.jitterFrame + 1; // Halton-Index 1..8 (Index 0 wäre (0,0) doppelt)
        dest.set(
                (halton(i, 2) - 0.5F) * 2F / width,
                (halton(i, 3) - 0.5F) * 2F / height);
        /* Fürs jitter-kompensierte Current-Sampling im Resolve: UV-Versatz = NDC/2 */
        this.context.jitterUv.set(dest.x * 0.5F, dest.y * 0.5F);
    }

    /** Radikal-invers zur Basis b — Standard-Jitter-Sequenz (gut verteilte Subpixel). */
    private static float halton(int index, int base) {
        float f = 1F, r = 0F;
        while (index > 0) {
            f /= base;
            r += f * (index % base);
            index /= base;
        }
        return r;
    }

    /** Kameradaten des Frames für die TAA-Reprojektion in den Context kopieren —
        nach {@code camera.update(...)} aufrufen (GameContainer.renderWorld). */
    public void updateTaaCamera(Camera camera) {
        this.context.invProjView.set(camera.getInvProjectionViewMatrix());
        this.context.prevProjView.set(camera.getPrevProjectionViewMatrix());
        this.context.camDelta.set(camera.getCamDelta());
    }

    public void resize(int width, int height) {
        this.context.resize(width, height);

        for (PostPass pass : this.passes) {
            pass.resize(this.context);
        }
    }

    /**
     * Führt die Kette aus: Szene ({@code frameBuffer}, bereits resolved) → Pässe → GuiScreen.
     * Erwartet den Default-Framebuffer als Endziel; lässt Depth-Test/Blend deaktiviert
     * zurück (die GUI setzt ihren State selbst, SkyEngine reaktiviert pro Frame).
     */
    public void render(FrameBuffer frameBuffer) {
        this.context.frame++;
        this.context.sceneColor = frameBuffer.getColorTexture();
        this.context.sceneDepth = frameBuffer.getPostDepthTexture();

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
        boolean postProfileActive = false;
        for (PostPass pass : this.passes) {
            if (!pass.isActive(this.context)) continue;
            boolean isLast = pass == last;
            this.context.input = input;
            this.context.targetFbo = isLast ? 0 : this.context.pingFbo(ping);
            if (!postProfileActive) {
                FrameProfiler.gpuBegin(FrameProfiler.Gpu.POSTPROCESSING);
                postProfileActive = true;
            }
            pass.execute(this.context);
            if (!isLast) {
                input = this.context.pingTexture(ping);
                ping = 1 - ping;
            }
        }
        if (postProfileActive) FrameProfiler.gpuEnd(FrameProfiler.Gpu.POSTPROCESSING);
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
