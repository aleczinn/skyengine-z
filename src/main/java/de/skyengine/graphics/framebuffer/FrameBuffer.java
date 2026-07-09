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
import org.lwjgl.opengl.NVFramebufferMultisampleCoverage;

public class FrameBuffer implements IDisposable {

    private final Logger logger = LogManager.getLogger(FrameBuffer.class.getName());

    private final EngineConfig config;
    private final EngineProperties properties;

    private int id;

    private int colorRbo;
    private int depthRbo;

    /** Coverage-Sample-Zahl (nur NV-Coverage-Pfad; muss >= der Color-Sample-Zahl sein). */
    private static final int COVERAGE_SAMPLES = 16;

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
        sonst MSAA (auf NVIDIA über den Coverage-Pfad, Coverage-Samples >= Color-Samples). */
    private void renderbufferStorage(int samples, int internalFormat) {
        int width = this.config.getWindowWidth(), height = this.config.getWindowHeight();

        if (samples <= 0) {
            GL30.glRenderbufferStorage(GL30.GL_RENDERBUFFER, internalFormat, width, height);
        } else if (this.properties.isUseNvMultisampleCoverage()) {
            NVFramebufferMultisampleCoverage.glRenderbufferStorageMultisampleCoverageNV(GL30.GL_RENDERBUFFER, Math.max(COVERAGE_SAMPLES, samples), samples, internalFormat, width, height);
        } else {
            GL30.glRenderbufferStorageMultisample(GL30.GL_RENDERBUFFER, samples, internalFormat, width, height);
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
