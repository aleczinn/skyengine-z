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
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL30;

/** Pack-provided HDR-to-display transform (Photon defaults to Lottes in Rec.2020). */
public final class ColorGradingPass implements PostPass, ShaderPackManager.Participant {
    private ShaderProgram program;
    private ShaderPackManager manager;

    @Override
    public void init(PostContext context) {
        this.manager = SkyEngine.get().getShaderPackManager();
        this.activate(this.prepare(this.manager.active()));
        this.manager.register(this);
    }

    @Override
    public ShaderPackManager.Prepared prepare(ShaderPack pack) {
        ShaderProgram candidate = new ShaderProgram(
                new Shader(PostProcessor.FULLSCREEN_VERTEX_SHADER, ShaderType.VERTEX),
                new Shader(this.manager.program(pack, "color_grading"), ShaderType.FRAGMENT));
        candidate.bind();
        candidate.setUniformi("u_Scene", 0);
        candidate.unbind();
        return new PreparedProgram(candidate);
    }

    @Override
    public void activate(ShaderPackManager.Prepared prepared) {
        ShaderProgram previous = this.program;
        this.program = ((PreparedProgram) prepared).take();
        if (previous != null) previous.dispose();
    }

    @Override
    public boolean isActive(PostContext context) {
        return true;
    }

    @Override
    public void execute(PostContext context) {
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, context.targetFbo);
        this.program.bind();
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, context.input);
        context.drawFullscreenTriangle();
        this.program.unbind();
    }

    @Override
    public void dispose() {
        if (this.manager != null) this.manager.unregister(this);
        if (this.program != null) this.program.dispose();
    }

    private static final class PreparedProgram implements ShaderPackManager.Prepared {
        private ShaderProgram program;
        private PreparedProgram(ShaderProgram program) { this.program = program; }
        private ShaderProgram take() {
            ShaderProgram result = this.program;
            this.program = null;
            return result;
        }
        @Override public void dispose() {
            if (this.program != null) this.program.dispose();
        }
    }
}
