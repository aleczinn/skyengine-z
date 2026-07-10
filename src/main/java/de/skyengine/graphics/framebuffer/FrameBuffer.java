package de.skyengine.graphics.framebuffer;

import de.skyengine.core.EngineConfig;
import de.skyengine.core.EngineProperties;
import de.skyengine.core.io.IDisposable;
import de.skyengine.core.settings.GameSettings;
import de.skyengine.utils.logging.LogManager;
import de.skyengine.utils.logging.Logger;
import org.lwjgl.opengl.ARBDirectStateAccess;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

public class FrameBuffer implements IDisposable {

    private final Logger logger = LogManager.getLogger(FrameBuffer.class.getName());

    private final EngineConfig config;
    private final EngineProperties properties;

    private int id;

    private int colorRbo;
    private int depthRbo;

    public FrameBuffer(EngineConfig config, EngineProperties properties) {
        this.config = config;
        this.properties = properties;
    }

    public void create() {
        if (this.id != 0) {
            this.dispose();
        }

        this.id = GL30.glGenFramebuffers();
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, this.id);

        /* MSAA-Sample-Zahl aus den Settings (0 = aus), an die Hardware-Grenze geklemmt. */
        int samples = Math.min(GameSettings.get().msaaSamples, GL11.glGetInteger(GL30.GL_MAX_SAMPLES));

        // Color Buffer
        this.colorRbo = GL30.glGenRenderbuffers();
        GL30.glBindRenderbuffer(GL30.GL_RENDERBUFFER, this.colorRbo);
        this.renderbufferStorage(samples, GL30.GL_RGBA8);
        this.logGrantedSamples(samples);
        GL30.glFramebufferRenderbuffer(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL30.GL_RENDERBUFFER, this.colorRbo);

        // Depth Buffer
        this.depthRbo = GL30.glGenRenderbuffers();
        GL30.glBindRenderbuffer(GL30.GL_RENDERBUFFER, this.depthRbo);
        this.renderbufferStorage(samples, GL30.GL_DEPTH_COMPONENT32F);
        GL30.glFramebufferRenderbuffer(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT, GL30.GL_RENDERBUFFER, depthRbo);

        // Check status
        int status = GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER);
        if (status != GL30.GL_FRAMEBUFFER_COMPLETE) {
            this.logger.error("Framebuffer is not complete! Status: " + status);
        }

        GL30.glBindRenderbuffer(GL30.GL_RENDERBUFFER, 0);
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);

        this.logger.debug("Create framebuffer with id " + this.id);
    }

    /** Legt den Storage des aktuell gebundenen Renderbuffers an: ohne Multisample bei 0 Samples,
        sonst MSAA. (Der frühere NV-CSAA-Pfad wurde entfernt — moderne Treiber runden die
        Coverage-Anfrage still auf volles 16x MSAA auf, gemessen auf RTX 4080.) */
    private void renderbufferStorage(int samples, int internalFormat) {
        int width = this.config.getWindowWidth(), height = this.config.getWindowHeight();

        if (samples <= 0) {
            GL30.glRenderbufferStorage(GL30.GL_RENDERBUFFER, internalFormat, width, height);
        } else {
            GL30.glRenderbufferStorageMultisample(GL30.GL_RENDERBUFFER, samples, internalFormat, width, height);
        }
    }

    /** Loggt die vom Treiber tatsächlich GEWÄHRTE Sample-Zahl des aktuell gebundenen
        Renderbuffers — Treiber dürfen Anfragen aufrunden. */
    private void logGrantedSamples(int requestedSamples) {
        if (requestedSamples <= 0) {
            this.logger.debug("MSAA: aus");
        } else {
            int granted = GL30.glGetRenderbufferParameteri(GL30.GL_RENDERBUFFER, GL30.GL_RENDERBUFFER_SAMPLES);
            this.logger.debug("MSAA: angefordert " + requestedSamples + " Samples — gewährt " + granted);
        }
    }

    public void bind() {
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, this.id);
    }

    public void unbind() {
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
    }

    /** Copy the content of this framebuffer to the default framebuffer (screen) */
    public void blitToScreen() {
        int width = this.config.getWindowWidth(), height = this.config.getWindowHeight();

        if (this.properties.isUseDirectStateAccess()) {
            ARBDirectStateAccess.glBlitNamedFramebuffer(this.id, 0, 0, 0, width, height, 0, 0, width, height, GL11.GL_COLOR_BUFFER_BIT, GL11.GL_NEAREST);
        } else {
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, this.id);
            GL30.glBlitFramebuffer(0, 0, width, height, 0, 0, width, height, GL11.GL_COLOR_BUFFER_BIT, GL11.GL_NEAREST);
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
        }
    }

    @Override
    public void dispose() {
        this.logger.debug("delete framebuffer with id " + this.id);

        GL30.glDeleteFramebuffers(this.id);
        GL30.glDeleteRenderbuffers(new int[] {this.colorRbo, this.depthRbo});
    }
}
