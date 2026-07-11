package de.skyengine.graphics.post;

import de.skyengine.core.io.IDisposable;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL30;

import java.nio.ByteBuffer;

/**
 * Gebündelte Ressourcen der Post-Processing-Kette mit <b>benannten Slots</b> — jeder Pass
 * bezieht Ein-/Ausgänge ausschließlich von hier. Slots, die es noch nicht gibt, sind 0
 * (dokumentiert): {@link #velocity}/{@link #history} kommen mit TAA (Phase 2), {@link #lut}
 * mit dem LUTPass; {@link #sceneDepth} ist nur bei MSAA=0 belegt.
 *
 * <p>Dazu: zwei LDR-Ping-Pong-Zwischentexturen (RGBA8) für Pass-Verkettung und der
 * gemeinsame Fullscreen-Triangle-Draw (leeres VAO, Positionen aus gl_VertexID).
 */
public final class PostContext implements IDisposable {

    /* --- Ressourcen-Slots (Textur-IDs, 0 = aktuell nicht vorhanden) --- */
    public int sceneColor;   // HDR-Szene (RGBA16F), bei MSAA erst nach FrameBuffer.resolve() aktuell
    public int sceneDepth;   // Szenen-Tiefe (32F, Reversed-Z) — nur bei MSAA=0
    public int velocity;     // reserviert: per-Objekt-Bewegungsvektoren (TAA nutzt bisher Kamera-Reprojektion)
    public int history;      // TAA-History des Frames (Write-Seite, vom AntiAliasingPass publiziert)
    public int lut;          // reserviert: 3D-LUT (LUTPass, display-referred nach Grading)

    /* --- Verkettung: vom PostProcessor vor jedem execute() gesetzt --- */
    public int input;        // Eingangstextur des aktuellen Passes
    public int targetFbo;    // Ziel-FBO des aktuellen Passes (0 = Default-Framebuffer/Screen)

    /* --- TAA-Kameradaten des Frames (PostProcessor.updateTaaCamera, s. Camera) --- */
    public final Matrix4f invProjView = new Matrix4f();  // Inverse der GEJITTERTEN PV
    public final Matrix4f prevProjView = new Matrix4f(); // UNGEJITTERTE PV des Vorframes
    public final Vector3f camDelta = new Vector3f();     // camNow − camPrev (kamerarelativ)

    /* Frame-Zähler (PostProcessor.render) — Pässe erkennen Aussetzer (History invalid). */
    public long frame;

    public PostProcessingSettings settings;
    public int width, height;

    private int emptyVao;
    private final int[] pingFbo = new int[2];
    private final int[] pingTex = new int[2];

    /** Render-Thread, GL-Kontext aktiv. */
    void create(int width, int height) {
        this.emptyVao = GL30.glGenVertexArrays();
        this.createPingTargets(width, height);
    }

    void resize(int width, int height) {
        this.disposePingTargets();
        this.createPingTargets(width, height);
    }

    private void createPingTargets(int width, int height) {
        this.width = width;
        this.height = height;
        for (int i = 0; i < 2; i++) {
            this.pingTex[i] = GL11.glGenTextures();
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, this.pingTex[i]);
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, width, height, 0,
                    GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);

            this.pingFbo[i] = GL30.glGenFramebuffers();
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, this.pingFbo[i]);
            GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0,
                    GL11.GL_TEXTURE_2D, this.pingTex[i], 0);
        }
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
    }

    /** LDR-Zwischenziel i (0/1) — FBO fürs Schreiben, {@link #pingTexture} fürs Lesen. */
    public int pingFbo(int i) {
        return this.pingFbo[i];
    }

    public int pingTexture(int i) {
        return this.pingTex[i];
    }

    /**
     * Zeichnet das Fullscreen-Dreieck (3 Vertices aus gl_VertexID, kein VBO).
     * Shader-Gegenstück: {@link PostProcessor#FULLSCREEN_VERTEX_SHADER}.
     */
    public void drawFullscreenTriangle() {
        GL30.glBindVertexArray(this.emptyVao);
        GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
        GL30.glBindVertexArray(0);
    }

    private void disposePingTargets() {
        for (int i = 0; i < 2; i++) {
            if (this.pingFbo[i] != 0) GL30.glDeleteFramebuffers(this.pingFbo[i]);
            if (this.pingTex[i] != 0) GL11.glDeleteTextures(this.pingTex[i]);
            this.pingFbo[i] = 0;
            this.pingTex[i] = 0;
        }
    }

    @Override
    public void dispose() {
        this.disposePingTargets();
        if (this.emptyVao != 0) {
            GL30.glDeleteVertexArrays(this.emptyVao);
            this.emptyVao = 0;
        }
    }
}
