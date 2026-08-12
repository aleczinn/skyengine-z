package de.skyengine.graphics.post.passes;

import de.skyengine.core.SkyEngine;
import de.skyengine.core.settings.GameSettings;
import de.skyengine.graphics.post.PostContext;
import de.skyengine.graphics.post.PostPass;
import de.skyengine.graphics.post.PostProcessor;
import de.skyengine.graphics.shader.Shader;
import de.skyengine.graphics.shader.ShaderProgram;
import de.skyengine.graphics.shader.ShaderType;
import de.skyengine.graphics.shaderpack.ShaderPack;
import de.skyengine.graphics.shaderpack.ShaderPackManager;
import de.skyengine.graphics.world.PhotonShadowMatrices;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

import java.nio.ByteBuffer;

/** Pack-gesteuerter Unterwasser-/Lavanebel vor Bloom und Farbkorrektur. */
public final class FluidFogPass implements PostPass, ShaderPackManager.Participant {
    public static final int NONE = 0;
    public static final int WATER = 1;
    public static final int LAVA = 2;

    private ShaderPackManager manager;
    private ShaderProgram airVolumetricProgram;
    private ShaderProgram volumetricProgram;
    private ShaderProgram compositeProgram;
    private int fluid;
    private int shadowDepthAll;
    private int shadowDepthSolid;
    private int waterNoiseTexture;
    private int atmosphereFogTexture;
    private int volumetricFbo;
    private int fogTransmittance;
    private int fogScattering;
    private int fogWidth;
    private int fogHeight;
    private float volumetricScale = 0.5F;
    private float eyeSkylight = 1.0F;
    private final Matrix4f waterLightMatrix = new Matrix4f();
    private final Matrix4f waterLightView = new Matrix4f();
    private long animationStartNanos = System.nanoTime();

    @Override
    public void init(PostContext context) {
        this.manager = SkyEngine.get().getShaderPackManager();
        this.activate(this.prepare(this.manager.active()));
        this.manager.register(this);
        this.createTargets(context.width, context.height);
    }

    @Override
    public ShaderPackManager.Prepared prepare(ShaderPack pack) {
        ShaderProgram airVolumetric = new ShaderProgram(
                new Shader(PostProcessor.FULLSCREEN_VERTEX_SHADER, ShaderType.VERTEX),
                new Shader(this.manager.program(pack, "air_vl"), ShaderType.FRAGMENT));
        airVolumetric.bind();
        airVolumetric.setUniformi("u_DepthFront", 0);
        airVolumetric.setUniformi("u_WaterNoise", 2);
        airVolumetric.setUniformi("u_ShadowDepthSolid", 4);
        airVolumetric.unbind();

        ShaderProgram volumetric = new ShaderProgram(
                new Shader(PostProcessor.FULLSCREEN_VERTEX_SHADER, ShaderType.VERTEX),
                new Shader(this.manager.program(pack, "water_vl"), ShaderType.FRAGMENT));
        volumetric.bind();
        volumetric.setUniformi("u_DepthFront", 0);
        volumetric.setUniformi("u_DepthBack", 1);
        volumetric.setUniformi("u_WaterNoise", 2);
        volumetric.setUniformi("u_ShadowDepthAll", 3);
        volumetric.setUniformi("u_ShadowDepthSolid", 4);
        volumetric.setUniformi("u_AtmosphereFog", 5);
        volumetric.unbind();

        ShaderProgram composite = new ShaderProgram(
                new Shader(PostProcessor.FULLSCREEN_VERTEX_SHADER, ShaderType.VERTEX),
                new Shader(this.manager.program(pack, "fluid_fog"), ShaderType.FRAGMENT));
        composite.bind();
        composite.setUniformi("u_Scene", 0);
        composite.setUniformi("u_FogTransmittance", 1);
        composite.setUniformi("u_FogScattering", 2);
        composite.setUniformi("u_HandDepth", 3);
        composite.unbind();

        float scale = 0.5F;
        var resource = pack.manifest().resources.get("water_fog_transmittance");
        if (resource != null) scale = (float) resource.scale;
        return new PreparedPrograms(airVolumetric, volumetric, composite, scale);
    }

    @Override
    public void activate(ShaderPackManager.Prepared prepared) {
        PreparedPrograms programs = (PreparedPrograms) prepared;
        ShaderProgram previousAirVolumetric = this.airVolumetricProgram;
        ShaderProgram previousVolumetric = this.volumetricProgram;
        ShaderProgram previousComposite = this.compositeProgram;
        this.airVolumetricProgram = programs.takeAirVolumetric();
        this.volumetricProgram = programs.takeVolumetric();
        this.compositeProgram = programs.takeComposite();
        float nextScale = programs.scale;
        if (nextScale != this.volumetricScale && this.fogWidth != 0) {
            this.volumetricScale = nextScale;
            this.disposeTargets();
            this.createTargets(SkyEngine.get().getWindow().getWidth(),
                    SkyEngine.get().getWindow().getHeight());
        } else {
            this.volumetricScale = nextScale;
        }
        if (previousAirVolumetric != null) previousAirVolumetric.dispose();
        if (previousVolumetric != null) previousVolumetric.dispose();
        if (previousComposite != null) previousComposite.dispose();
    }

    public void setFluid(int fluid) {
        this.fluid = Math.clamp(fluid, NONE, LAVA);
    }

    public int fluid() {
        return this.fluid;
    }

    public void setEyeSkylight(float skylight) {
        this.eyeSkylight = Math.clamp(skylight, 0F, 1F);
    }

    public void setAtmosphereFogTexture(int texture) {
        this.atmosphereFogTexture = texture;
    }

    public void setWaterLightMap(int depthAll, int depthSolid, Matrix4f lightMatrix,
                                 Matrix4f lightView, int noiseTexture) {
        this.shadowDepthAll = depthAll;
        this.shadowDepthSolid = depthSolid;
        this.waterNoiseTexture = noiseTexture;
        this.waterLightMatrix.set(lightMatrix);
        this.waterLightView.set(lightView);
    }

    @Override
    public boolean isActive(PostContext context) {
        /* Photon c0_vl also runs in clear air; water and lava merely select other media. */
        return this.fluid != NONE || this.atmosphereFogTexture != 0;
    }

    @Override
    public void resize(PostContext context) {
        this.disposeTargets();
        this.createTargets(context.width, context.height);
    }

    @Override
    public void execute(PostContext context) {
        if (this.fluid == NONE) this.executeVolumetrics(context, this.airVolumetricProgram);
        if (this.fluid == WATER) this.executeVolumetrics(context, this.volumetricProgram);

        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, context.targetFbo);
        GL11.glViewport(0, 0, context.width, context.height);
        this.compositeProgram.bind();
        this.compositeProgram.setUniformi("u_Fluid", this.fluid);
        this.compositeProgram.setUniformf("u_ZeroToOneDepth",
                SkyEngine.get().getWindow().getProperties().isUseInverseDepth() ? 1F : 0F);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, context.input);
        GL13.glActiveTexture(GL13.GL_TEXTURE1);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, this.fogTransmittance);
        GL13.glActiveTexture(GL13.GL_TEXTURE2);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, this.fogScattering);
        GL13.glActiveTexture(GL13.GL_TEXTURE3);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, context.handDepth);
        context.drawFullscreenTriangle();
        this.compositeProgram.unbind();
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
    }

    private void executeVolumetrics(PostContext context, ShaderProgram program) {
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, this.volumetricFbo);
        GL11.glViewport(0, 0, this.fogWidth, this.fogHeight);
        GL11.glClearColor(0F, 0F, 0F, 0F);
        GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);
        program.bind();
        this.manager.applySettings(program);
        program.setUniformMatrix4f("u_InvProjectionView", context.invProjView);
        program.setUniformVector2f("u_JitterUv", context.jitterUv);
        program.setUniformMatrix4f("u_ShadowProjectionView", this.waterLightMatrix);
        program.setUniformMatrix4f("u_ShadowView", this.waterLightView);
        program.setUniformVector3f("u_CameraPosition",
                context.cameraPosition.x, context.cameraPosition.y, context.cameraPosition.z);
        /* -shadowProjectionInverse[2].z der klassischen Photon-Orthoprojektion. */
        program.setUniformf("u_ShadowDepthRange", PhotonShadowMatrices.SHADOW_DEPTH_RANGE);
        program.setUniformf("u_Time",
                (System.nanoTime() - this.animationStartNanos) * 1.0e-9F);
        program.setUniformf("u_Frame", (float) (context.frame & 4095L));
        program.setUniformf("u_ZeroToOneDepth",
                SkyEngine.get().getWindow().getProperties().isUseInverseDepth() ? 1F : 0F);
        program.setUniformf("u_EyeSkylight", this.eyeSkylight);
        GameSettings settings = GameSettings.get();
        float fogEnd = (settings.lodEnabled
                ? Math.max(settings.renderDistance, settings.lodMaxDistance)
                : settings.renderDistance) * 32.0F;
        program.setUniformf("u_FogEnd", fogEnd);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        /* Air is composited after translucent geometry. Using the opaque depth preserves
           Photon's fog-behind-water ordering; underwater uses the nearest scene depth. */
        GL11.glBindTexture(GL11.GL_TEXTURE_2D,
                this.fluid == NONE ? context.worldDepth : context.sceneDepth);
        GL13.glActiveTexture(GL13.GL_TEXTURE1);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, context.worldDepth);
        GL13.glActiveTexture(GL13.GL_TEXTURE2);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, this.waterNoiseTexture);
        GL13.glActiveTexture(GL13.GL_TEXTURE3);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, this.shadowDepthAll);
        GL13.glActiveTexture(GL13.GL_TEXTURE4);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, this.shadowDepthSolid);
        GL13.glActiveTexture(GL13.GL_TEXTURE5);
        GL11.glBindTexture(GL13.GL_TEXTURE_CUBE_MAP, this.atmosphereFogTexture);
        context.drawFullscreenTriangle();
        program.unbind();
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
    }

    private void createTargets(int width, int height) {
        this.fogWidth = Math.max(1, Math.round(width * this.volumetricScale));
        this.fogHeight = Math.max(1, Math.round(height * this.volumetricScale));
        this.fogTransmittance = this.createFogTexture();
        this.fogScattering = this.createFogTexture();
        this.volumetricFbo = GL30.glGenFramebuffers();
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, this.volumetricFbo);
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0,
                GL11.GL_TEXTURE_2D, this.fogTransmittance, 0);
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT1,
                GL11.GL_TEXTURE_2D, this.fogScattering, 0);
        GL20.glDrawBuffers(new int[]{GL30.GL_COLOR_ATTACHMENT0, GL30.GL_COLOR_ATTACHMENT1});
        if (GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER) != GL30.GL_FRAMEBUFFER_COMPLETE) {
            throw new IllegalStateException("Photon-Wasser-VL-Framebuffer ist unvollständig");
        }
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
    }

    private int createFogTexture() {
        int texture = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_RGB16F,
                this.fogWidth, this.fogHeight, 0, GL11.GL_RGB, GL11.GL_FLOAT,
                (ByteBuffer) null);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        return texture;
    }

    private void disposeTargets() {
        if (this.volumetricFbo != 0) GL30.glDeleteFramebuffers(this.volumetricFbo);
        if (this.fogTransmittance != 0) GL11.glDeleteTextures(this.fogTransmittance);
        if (this.fogScattering != 0) GL11.glDeleteTextures(this.fogScattering);
        this.volumetricFbo = 0;
        this.fogTransmittance = 0;
        this.fogScattering = 0;
        this.fogWidth = 0;
        this.fogHeight = 0;
    }

    @Override
    public void dispose() {
        if (this.manager != null) this.manager.unregister(this);
        this.disposeTargets();
        if (this.airVolumetricProgram != null) this.airVolumetricProgram.dispose();
        if (this.volumetricProgram != null) this.volumetricProgram.dispose();
        if (this.compositeProgram != null) this.compositeProgram.dispose();
    }

    private static final class PreparedPrograms implements ShaderPackManager.Prepared {
        private ShaderProgram airVolumetric;
        private ShaderProgram volumetric;
        private ShaderProgram composite;
        private final float scale;

        private PreparedPrograms(ShaderProgram airVolumetric, ShaderProgram volumetric,
                                 ShaderProgram composite, float scale) {
            this.airVolumetric = airVolumetric;
            this.volumetric = volumetric;
            this.composite = composite;
            this.scale = scale;
        }

        private ShaderProgram takeAirVolumetric() {
            ShaderProgram result = this.airVolumetric;
            this.airVolumetric = null;
            return result;
        }

        private ShaderProgram takeVolumetric() {
            ShaderProgram result = this.volumetric;
            this.volumetric = null;
            return result;
        }

        private ShaderProgram takeComposite() {
            ShaderProgram result = this.composite;
            this.composite = null;
            return result;
        }

        @Override
        public void dispose() {
            if (this.airVolumetric != null) this.airVolumetric.dispose();
            if (this.volumetric != null) this.volumetric.dispose();
            if (this.composite != null) this.composite.dispose();
        }
    }
}
