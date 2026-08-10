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
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL30;

/** Pack-gesteuerter Unterwasser-/Lavanebel vor Bloom und Farbkorrektur. */
public final class FluidFogPass implements PostPass, ShaderPackManager.Participant {
    public static final int NONE = 0;
    public static final int WATER = 1;
    public static final int LAVA = 2;

    private ShaderPackManager manager;
    private ShaderProgram program;
    private int fluid;
    private int waterShadowTexture;
    private int waterNoiseTexture;
    private final Matrix4f waterLightMatrix = new Matrix4f();
    private long animationStartNanos = System.nanoTime();

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
                new Shader(pack.program("fluid_fog"), ShaderType.FRAGMENT));
        candidate.bind();
        candidate.setUniformi("u_Scene", 0);
        candidate.setUniformi("u_Depth", 1);
        candidate.setUniformi("u_WaterNoise", 2);
        candidate.setUniformi("u_WaterShadow", 3);
        candidate.setUniformi("u_WorldDepth", 4);
        candidate.unbind();
        return new PreparedProgram(candidate);
    }

    @Override
    public void activate(ShaderPackManager.Prepared prepared) {
        ShaderProgram previous = this.program;
        this.program = ((PreparedProgram) prepared).take();
        if (previous != null) previous.dispose();
    }

    public void setFluid(int fluid) {
        this.fluid = Math.clamp(fluid, NONE, LAVA);
    }

    public int fluid() {
        return this.fluid;
    }

    public void setWaterLightMap(int shadowTexture, Matrix4f lightMatrix, int noiseTexture) {
        this.waterShadowTexture = shadowTexture;
        this.waterNoiseTexture = noiseTexture;
        this.waterLightMatrix.set(lightMatrix);
    }

    @Override
    public boolean isActive(PostContext context) {
        return this.fluid != NONE;
    }

    @Override
    public void execute(PostContext context) {
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, context.targetFbo);
        this.program.bind();
        this.manager.applySettings(this.program);
        this.program.setUniformi("u_Fluid", this.fluid);
        this.program.setUniformMatrix4f("u_InvProjectionView", context.invProjView);
        this.program.setUniformMatrix4f("u_WaterLightProjection", this.waterLightMatrix);
        this.program.setUniformVector2f("u_Viewport", context.width, context.height);
        this.program.setUniformf("u_Time", (System.nanoTime() - this.animationStartNanos) * 1.0e-9F);
        this.program.setUniformf("u_ZeroToOneDepth",
                SkyEngine.get().getWindow().getProperties().isUseInverseDepth() ? 1F : 0F);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, context.input);
        GL13.glActiveTexture(GL13.GL_TEXTURE1);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, context.sceneDepth);
        GL13.glActiveTexture(GL13.GL_TEXTURE2);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, this.waterNoiseTexture);
        GL13.glActiveTexture(GL13.GL_TEXTURE3);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, this.waterShadowTexture);
        GL13.glActiveTexture(GL13.GL_TEXTURE4);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, context.worldDepth);
        context.drawFullscreenTriangle();
        this.program.unbind();
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
    }

    @Override
    public void dispose() {
        if (this.manager != null) this.manager.unregister(this);
        if (this.program != null) this.program.dispose();
    }

    private static final class PreparedProgram implements ShaderPackManager.Prepared {
        private ShaderProgram program;

        private PreparedProgram(ShaderProgram program) {
            this.program = program;
        }

        private ShaderProgram take() {
            ShaderProgram result = this.program;
            this.program = null;
            return result;
        }

        @Override
        public void dispose() {
            if (this.program != null) this.program.dispose();
        }
    }
}
