package de.skyengine.graphics.framebuffer;

import de.skyengine.core.EngineConfig;
import de.skyengine.core.EngineProperties;
import de.skyengine.core.io.IDisposable;
import de.skyengine.core.settings.GameSettings;
import de.skyengine.utils.logging.LogManager;
import de.skyengine.utils.logging.Logger;
import org.lwjgl.opengl.ARBDirectStateAccess;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL30;

import java.nio.ByteBuffer;

/**
 * Szene-Render-Target der Post-Processing-Pipeline. Zwei Betriebsarten:
 * <ul>
 *   <li><b>MSAA = 0:</b> Color (RGBA16F) und Depth (DEPTH_COMPONENT32F) sind direkt
 *       <b>Texturen</b> — sample-bar für Post-Pässe; {@link #resolve()} ist ein No-op.</li>
 *   <li><b>MSAA &gt; 0:</b> Multisample-Renderbuffer wie früher (Color ebenfalls RGBA16F,
 *       damit HDR-Werte den Resolve überleben) plus ein Resolve-FBO mit RGBA16F-Farbe und
 *       DEPTH_COMPONENT32F; {@link #resolve()} blittet beide Attachments MS → Textur.</li>
 * </ul>
 * RGBA16F speichert heute dieselben [0,1]-Werte wie vorher RGBA8 — der HDR-Headroom wird
 * erst mit dem Licht-Merge real genutzt.
 */
public class FrameBuffer implements IDisposable {

    private final Logger logger = LogManager.getLogger(FrameBuffer.class.getName());

    private final EngineConfig config;
    private final EngineProperties properties;

    private int id;

    /* MSAA-Pfad (samples > 0) */
    private int colorRbo;
    private int depthRbo;
    private int resolveFbo;

    /* Unveränderter Stand direkt vor dem Translucent-Pass. Wasser darf das aktuell
       gebundene Szene-Attachment nicht gleichzeitig lesen und beschreiben (Feedback-UB). */
    private int opaqueFbo;
    private int opaqueColorTexture;
    private int opaqueDepthTexture;

    /* Tiefe der fertig gerenderten Welt VOR dem First-Person-Hand-Pass. Der Hand-Renderer
       leert absichtlich den Szene-Depthbuffer, damit Arm/Item nie hinter Terrain verschwinden.
       Diese Kopie verhindert, dass TAA danach fast ueberall nur die Clear-Tiefe und im
       Handbereich eine bewegte rechteckige Tiefensilhouette reprojiziert. */
    private int worldDepthFbo;
    private int worldDepthTexture;

    /* Tiefe ausschliesslich des First-Person-Passes. Der Hand-Renderer leert vor seinem
       Draw den aktiven Szene-Depthbuffer; eine danach angelegte Kopie ist deshalb eine
       exakte Hand-/Item-Maske. Photon fuehrt die Hand ebenfalls getrennt und laesst weder
       Luft- noch Fluid-Nebel durch sie hindurch compositen. */
    private int handDepthFbo;
    private int handDepthTexture;

    /* Sample-bare Ziele: colorTexture ist bei MSAA=0 direktes Attachment, sonst Resolve-Ziel. */
    private int colorTexture;
    private int depthTexture;

    private int samples;

    public FrameBuffer(EngineConfig config, EngineProperties properties) {
        this.config = config;
        this.properties = properties;
    }

    public void create() {
        if (this.id != 0) {
            this.dispose();
        }

        int width = this.config.getWindowWidth(), height = this.config.getWindowHeight();

        /* Die Sample-Zahl folgt dem AA-MODUS (PostProcessingSettings): nur aaMode=MSAA
           multisampelt (Qualität aus GameSettings.msaaSamples, 0 → 4); alle anderen Modi
           rendern ohne MSAA und haben damit die sample-bare Depth-Textur für TAA.
           this.samples speichert den WUNSCH (ungeklemmt) — der Neuaufbau-Vergleich in
           SkyEngine.onRender bleibt so stabil, auch wenn die Hardware weniger gewährt. */
        de.skyengine.graphics.post.PostProcessor post = de.skyengine.core.SkyEngine.get().getPostProcessor();
        this.samples = post != null && post.getSettings() != null
                ? post.getSettings().effectiveMsaaSamples(GameSettings.get().msaaSamples)
                : GameSettings.get().msaaSamples;
        int granted = Math.min(this.samples, GL11.glGetInteger(GL30.GL_MAX_SAMPLES));

        this.id = GL30.glGenFramebuffers();
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, this.id);

        if (this.samples <= 0) {
            this.logger.debug("MSAA: aus");

            /* Color + Depth direkt als Texturen — sample-bar für die Post-Pässe. */
            this.colorTexture = this.createTexture(GL30.GL_RGBA16F, GL11.GL_RGBA, GL30.GL_HALF_FLOAT, GL11.GL_LINEAR, width, height);
            GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, this.colorTexture, 0);

            this.depthTexture = this.createTexture(GL30.GL_DEPTH_COMPONENT32F, GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, GL11.GL_NEAREST, width, height);
            GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT, GL11.GL_TEXTURE_2D, this.depthTexture, 0);
        } else {
            /* Multisample-Renderbuffer; Color als RGBA16F, damit HDR-Werte den Resolve überleben. */
            this.colorRbo = GL30.glGenRenderbuffers();
            GL30.glBindRenderbuffer(GL30.GL_RENDERBUFFER, this.colorRbo);
            GL30.glRenderbufferStorageMultisample(GL30.GL_RENDERBUFFER, granted, GL30.GL_RGBA16F, width, height);
            this.logGrantedSamples(granted);
            GL30.glFramebufferRenderbuffer(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL30.GL_RENDERBUFFER, this.colorRbo);

            this.depthRbo = GL30.glGenRenderbuffers();
            GL30.glBindRenderbuffer(GL30.GL_RENDERBUFFER, this.depthRbo);
            GL30.glRenderbufferStorageMultisample(GL30.GL_RENDERBUFFER, granted, GL30.GL_DEPTH_COMPONENT32F, width, height);
            GL30.glFramebufferRenderbuffer(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT, GL30.GL_RENDERBUFFER, this.depthRbo);

            GL30.glBindRenderbuffer(GL30.GL_RENDERBUFFER, 0);
        }

        this.checkStatus("Szene");

        /* Resolve-FBO (nur MSAA): Non-MS-RGBA16F-Textur als Blit-Ziel für die Post-Pässe. */
        if (this.samples > 0) {
            this.resolveFbo = GL30.glGenFramebuffers();
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, this.resolveFbo);
            this.colorTexture = this.createTexture(GL30.GL_RGBA16F, GL11.GL_RGBA, GL30.GL_HALF_FLOAT,
                    GL11.GL_LINEAR, width, height);
            GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0,
                    GL11.GL_TEXTURE_2D, this.colorTexture, 0);
            this.depthTexture = this.createTexture(GL30.GL_DEPTH_COMPONENT32F, GL11.GL_DEPTH_COMPONENT,
                    GL11.GL_FLOAT, GL11.GL_NEAREST, width, height);
            GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT,
                    GL11.GL_TEXTURE_2D, this.depthTexture, 0);
            this.checkStatus("Resolve");
        }

        /* Opaque-Snapshot für pack-gesteuerte Refraction/SSR. Er existiert in beiden
           AA-Pfaden; bei MSAA wird beim Blit zugleich auf ein Sample aufgelöst. */
        this.opaqueFbo = GL30.glGenFramebuffers();
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, this.opaqueFbo);
        this.opaqueColorTexture = this.createTexture(GL30.GL_RGBA16F, GL11.GL_RGBA,
                GL30.GL_HALF_FLOAT, GL11.GL_LINEAR, width, height);
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0,
                GL11.GL_TEXTURE_2D, this.opaqueColorTexture, 0);
        this.opaqueDepthTexture = this.createTexture(GL30.GL_DEPTH_COMPONENT32F, GL11.GL_DEPTH_COMPONENT,
                GL11.GL_FLOAT, GL11.GL_NEAREST, width, height);
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT,
                GL11.GL_TEXTURE_2D, this.opaqueDepthTexture, 0);
        this.checkStatus("Opaque-Snapshot");

        /* Reiner Depth-Snapshot fuer die TAA-Reprojektion. Anders als opaqueDepth enthaelt
           er auch die bis dahin gerenderte Wasseroberflaeche; anders als depthTexture wird
           er vom anschliessenden First-Person-Depth-Clear nicht zerstoert. */
        this.worldDepthFbo = GL30.glGenFramebuffers();
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, this.worldDepthFbo);
        this.worldDepthTexture = this.createTexture(GL30.GL_DEPTH_COMPONENT32F,
                GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, GL11.GL_NEAREST, width, height);
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT,
                GL11.GL_TEXTURE_2D, this.worldDepthTexture, 0);
        GL11.glDrawBuffer(GL11.GL_NONE);
        GL11.glReadBuffer(GL11.GL_NONE);
        this.checkStatus("World-Depth-Snapshot");

        this.handDepthFbo = GL30.glGenFramebuffers();
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, this.handDepthFbo);
        this.handDepthTexture = this.createTexture(GL30.GL_DEPTH_COMPONENT32F,
                GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, GL11.GL_NEAREST, width, height);
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT,
                GL11.GL_TEXTURE_2D, this.handDepthTexture, 0);
        GL11.glDrawBuffer(GL11.GL_NONE);
        GL11.glReadBuffer(GL11.GL_NONE);
        this.checkStatus("Hand-Depth-Snapshot");

        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);

        this.logger.debug("Create framebuffer with id " + this.id
                + (this.samples > 0 ? " (MSAA " + this.samples + "x + Resolve-FBO " + this.resolveFbo + ")" : " (Texturen)"));
    }

    /**
     * Legt eine 2D-Textur ohne Mips an (CLAMP_TO_EDGE) und lässt sie gebunden zurück.
     */
    private int createTexture(int internalFormat, int format, int type, int filter, int width, int height) {
        int tex = GL11.glGenTextures();
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, tex);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, internalFormat, width, height, 0, format, type, (ByteBuffer) null);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, filter);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, filter);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
        return tex;
    }

    private void checkStatus(String name) {
        int status = GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER);
        if (status != GL30.GL_FRAMEBUFFER_COMPLETE) {
            this.logger.error(name + "-Framebuffer is not complete! Status: " + status);
        }
    }

    /**
     * Loggt die vom Treiber tatsächlich GEWÄHRTE Sample-Zahl des aktuell gebundenen
     * Renderbuffers — Treiber dürfen Anfragen aufrunden.
     */
    private void logGrantedSamples(int requestedSamples) {
        int granted = GL30.glGetRenderbufferParameteri(GL30.GL_RENDERBUFFER, GL30.GL_RENDERBUFFER_SAMPLES);
        this.logger.debug("MSAA: angefordert " + requestedSamples + " Samples — gewährt " + granted);
    }

    public void bind() {
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, this.id);
    }

    public void unbind() {
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
    }

    /**
     * Macht die Szene als {@link #getColorTexture()} sample-bar: bei MSAA Blit MS → Resolve-
     * Textur, ohne MSAA No-op (Color ist bereits die Textur). Vor den Post-Pässen aufrufen.
     */
    public void resolve() {
        if (this.samples <= 0) return;

        int width = this.config.getWindowWidth(), height = this.config.getWindowHeight();
        if (this.properties.isUseDirectStateAccess()) {
            ARBDirectStateAccess.glBlitNamedFramebuffer(this.id, this.resolveFbo,
                    0, 0, width, height, 0, 0, width, height,
                    GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT, GL11.GL_NEAREST);
        } else {
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, this.id);
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, this.resolveFbo);
            GL30.glBlitFramebuffer(0, 0, width, height, 0, 0, width, height,
                    GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT, GL11.GL_NEAREST);
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
        }
    }

    /**
     * Friert Farbe und Tiefe des bislang gerenderten Weltbildes für Wasser-Refraction ein.
     * Danach ist wieder das Szene-FBO gebunden.
     */
    public void captureOpaque() {
        int width = this.config.getWindowWidth(), height = this.config.getWindowHeight();
        if (this.properties.isUseDirectStateAccess()) {
            ARBDirectStateAccess.glBlitNamedFramebuffer(this.id, this.opaqueFbo,
                    0, 0, width, height, 0, 0, width, height,
                    GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT, GL11.GL_NEAREST);
        } else {
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, this.id);
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, this.opaqueFbo);
            GL30.glBlitFramebuffer(0, 0, width, height, 0, 0, width, height,
                    GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT, GL11.GL_NEAREST);
        }
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, this.id);
    }

    /**
     * Friert die Tiefe der vollstaendigen Welt (einschliesslich Wasser) ein, bevor der
     * First-Person-Pass den aktiven Depthbuffer leert. Danach ist wieder das Szene-FBO
     * gebunden. Muss einmal pro Welt-Frame nach {@code World.render} aufgerufen werden.
     */
    public void captureWorldDepth() {
        int width = this.config.getWindowWidth(), height = this.config.getWindowHeight();
        if (this.properties.isUseDirectStateAccess()) {
            ARBDirectStateAccess.glBlitNamedFramebuffer(this.id, this.worldDepthFbo,
                    0, 0, width, height, 0, 0, width, height,
                    GL11.GL_DEPTH_BUFFER_BIT, GL11.GL_NEAREST);
        } else {
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, this.id);
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, this.worldDepthFbo);
            GL30.glBlitFramebuffer(0, 0, width, height, 0, 0, width, height,
                    GL11.GL_DEPTH_BUFFER_BIT, GL11.GL_NEAREST);
        }
        /* Kein First-Person-Draw in diesem Frame bedeutet eine leere Handmaske. Sie wird
           hier immer initialisiert und nach einem tatsaechlichen Hand-Draw ueberschrieben. */
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, this.handDepthFbo);
        GL11.glClearDepth(this.properties.isUseInverseDepth() ? 0.0 : 1.0);
        GL11.glClear(GL11.GL_DEPTH_BUFFER_BIT);
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, this.id);
    }

    /**
     * Kopiert nach dem First-Person-Draw dessen isolierte Tiefe. Da der Hand-Renderer den
     * Szene-Depthbuffer unmittelbar davor leert, enthaelt diese Textur weder Welt noch Wasser.
     */
    public void captureHandDepth() {
        int width = this.config.getWindowWidth(), height = this.config.getWindowHeight();
        if (this.properties.isUseDirectStateAccess()) {
            ARBDirectStateAccess.glBlitNamedFramebuffer(this.id, this.handDepthFbo,
                    0, 0, width, height, 0, 0, width, height,
                    GL11.GL_DEPTH_BUFFER_BIT, GL11.GL_NEAREST);
        } else {
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, this.id);
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, this.handDepthFbo);
            GL30.glBlitFramebuffer(0, 0, width, height, 0, 0, width, height,
                    GL11.GL_DEPTH_BUFFER_BIT, GL11.GL_NEAREST);
        }
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, this.id);
    }

    /**
     * Sample-bare Szenen-Farbe (RGBA16F): bei MSAA erst nach {@link #resolve()} aktuell.
     */
    /** GL-Name des (Multisample-)Szene-FBO; 0 = noch nicht erzeugt. */
    public int getId() {
        return this.id;
    }

    public int getColorTexture() {
        return this.colorTexture;
    }

    /**
     * Sample-bare Szenen-Tiefe (32F) — nur bei MSAA=0 vorhanden, sonst 0.
     */
    public int getDepthTexture() {
        return this.depthTexture;
    }

    public int getOpaqueColorTexture() {
        return this.opaqueColorTexture;
    }

    public int getOpaqueDepthTexture() {
        return this.opaqueDepthTexture;
    }

    public int getWorldDepthTexture() {
        return this.worldDepthTexture;
    }

    public int getHandDepthTexture() {
        return this.handDepthTexture;
    }

    /**
     * Aktive MSAA-Sample-Zahl (0 = aus).
     */
    public int getSamples() {
        return this.samples;
    }

    @Override
    public void dispose() {
        this.logger.debug("delete framebuffer with id " + this.id);

        GL30.glDeleteFramebuffers(this.id);
        if (this.resolveFbo != 0) GL30.glDeleteFramebuffers(this.resolveFbo);
        if (this.opaqueFbo != 0) GL30.glDeleteFramebuffers(this.opaqueFbo);
        if (this.worldDepthFbo != 0) GL30.glDeleteFramebuffers(this.worldDepthFbo);
        if (this.handDepthFbo != 0) GL30.glDeleteFramebuffers(this.handDepthFbo);
        if (this.colorRbo != 0 || this.depthRbo != 0) {
            GL30.glDeleteRenderbuffers(new int[]{this.colorRbo, this.depthRbo});
        }
        if (this.colorTexture != 0) GL11.glDeleteTextures(this.colorTexture);
        if (this.depthTexture != 0) GL11.glDeleteTextures(this.depthTexture);
        if (this.opaqueColorTexture != 0) GL11.glDeleteTextures(this.opaqueColorTexture);
        if (this.opaqueDepthTexture != 0) GL11.glDeleteTextures(this.opaqueDepthTexture);
        if (this.worldDepthTexture != 0) GL11.glDeleteTextures(this.worldDepthTexture);
        if (this.handDepthTexture != 0) GL11.glDeleteTextures(this.handDepthTexture);

        this.id = 0;
        this.resolveFbo = 0;
        this.opaqueFbo = 0;
        this.worldDepthFbo = 0;
        this.handDepthFbo = 0;
        this.colorRbo = 0;
        this.depthRbo = 0;
        this.colorTexture = 0;
        this.depthTexture = 0;
        this.opaqueColorTexture = 0;
        this.opaqueDepthTexture = 0;
        this.worldDepthTexture = 0;
        this.handDepthTexture = 0;
    }
}
