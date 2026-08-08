package de.skyengine.graphics.world;

import de.skyengine.core.SkyEngine;
import de.skyengine.game.world.environment.EnvironmentProfile;
import de.skyengine.graphics.FrameProfiler;
import de.skyengine.graphics.GlDebug;
import de.skyengine.graphics.camera.Camera;
import de.skyengine.graphics.shader.Shader;
import de.skyengine.graphics.shader.ShaderProgram;
import de.skyengine.graphics.shader.ShaderType;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL30;
import org.lwjgl.BufferUtils;

import java.nio.IntBuffer;

/** Performanter Fullscreen-Pass für Atmosphäre, Sonne, Mond und Sterne. */
public final class SkyRenderer {
    private static final int LUT_WIDTH = 256;
    private static final int LUT_HEIGHT = 128;
    private static final int LUT_TEXTURE_UNIT = 7;
    private final EnvironmentProfile profile;
    private ShaderProgram shader;
    private ShaderProgram lutShader;
    private int vao;
    private int lutTexture;
    private int lutFramebuffer;
    private final IntBuffer viewport = BufferUtils.createIntBuffer(4);
    private int locInvProjectionView;
    private int locFarDepth;
    private int locDayFraction;

    public SkyRenderer(EnvironmentProfile profile) {
        this.profile = profile;
    }

    public void init() {
        this.shader = new ShaderProgram(new Shader(VERTEX_SOURCE, ShaderType.VERTEX),
                new Shader(FRAGMENT_SOURCE, ShaderType.FRAGMENT));
        this.locInvProjectionView = this.shader.getUniformLocation("u_InvProjectionView");
        this.locFarDepth = this.shader.getUniformLocation("u_FarDepth");
        this.locDayFraction = this.shader.getUniformLocation("u_DayFraction");
        this.shader.bind();
        this.shader.setUniformf("u_SunIntensity", this.profile.sunIntensity());
        this.shader.setUniformf("u_MoonIntensity", this.profile.moonIntensity());
        this.shader.setUniformi("u_SkyView", LUT_TEXTURE_UNIT);
        this.shader.unbind();
        this.lutShader = new ShaderProgram(new Shader(LUT_VERTEX_SOURCE, ShaderType.VERTEX),
                new Shader(LUT_FRAGMENT_SOURCE, ShaderType.FRAGMENT));
        this.lutShader.bind();
        this.lutShader.setUniformVector3f("u_Rayleigh", this.profile.rayleighR(),
                this.profile.rayleighG(), this.profile.rayleighB());
        this.lutShader.setUniformf("u_MieStrength", this.profile.mieStrength());
        this.lutShader.setUniformf("u_MieG", this.profile.mieG());
        this.lutShader.setUniformVector3f("u_NightSky", this.profile.nightSkyR(),
                this.profile.nightSkyG(), this.profile.nightSkyB());
        this.lutShader.unbind();
        this.vao = GL30.glGenVertexArrays();
        GL30.glBindVertexArray(this.vao);
        GlDebug.labelVertexArray(this.vao, "Atmospheric sky VAO");
        GL30.glBindVertexArray(0);

        this.lutTexture = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, this.lutTexture);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_RGBA16F, LUT_WIDTH, LUT_HEIGHT,
                0, GL11.GL_RGBA, GL11.GL_FLOAT, 0L);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
        GlDebug.labelTexture(this.lutTexture, "Sky-view LUT");
        int previousFramebuffer = GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING);
        this.lutFramebuffer = GL30.glGenFramebuffers();
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, this.lutFramebuffer);
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0,
                GL11.GL_TEXTURE_2D, this.lutTexture, 0);
        if (GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER) != GL30.GL_FRAMEBUFFER_COMPLETE) {
            throw new IllegalStateException("Sky-view LUT framebuffer is incomplete");
        }
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, previousFramebuffer);
    }

    public void render(Camera camera, float dayFraction) {
        this.updateLut();
        boolean inverseDepth = SkyEngine.get().getWindow().getProperties().isUseInverseDepth();
        int previousDepthFunc = GL11.glGetInteger(GL11.GL_DEPTH_FUNC);
        this.shader.bind();
        this.shader.setUniformMatrix4f(this.locInvProjectionView, camera.getInvProjectionViewMatrix());
        this.shader.setUniformf(this.locFarDepth, inverseDepth ? 0F : 1F);
        this.shader.setUniformf(this.locDayFraction, dayFraction);
        GL13.glActiveTexture(GL13.GL_TEXTURE0 + LUT_TEXTURE_UNIT);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, this.lutTexture);
        GL11.glDepthMask(false);
        GL11.glDepthFunc(inverseDepth ? GL11.GL_GEQUAL : GL11.GL_LEQUAL);
        GL30.glBindVertexArray(this.vao);
        FrameProfiler.gpuBegin(FrameProfiler.Gpu.SKY);
        GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
        FrameProfiler.gpuEnd(FrameProfiler.Gpu.SKY);
        GL30.glBindVertexArray(0);
        GL11.glDepthFunc(previousDepthFunc);
        GL11.glDepthMask(true);
        this.shader.unbind();
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
    }

    private void updateLut() {
        int previousFramebuffer = GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING);
        boolean depthTest = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
        boolean blending = GL11.glIsEnabled(GL11.GL_BLEND);
        this.viewport.clear();
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, this.viewport);
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, this.lutFramebuffer);
        GL11.glViewport(0, 0, LUT_WIDTH, LUT_HEIGHT);
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glDisable(GL11.GL_BLEND);
        this.lutShader.bind();
        GL30.glBindVertexArray(this.vao);
        FrameProfiler.gpuBegin(FrameProfiler.Gpu.SKY_LUT);
        GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
        FrameProfiler.gpuEnd(FrameProfiler.Gpu.SKY_LUT);
        GL30.glBindVertexArray(0);
        this.lutShader.unbind();
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, previousFramebuffer);
        GL11.glViewport(this.viewport.get(0), this.viewport.get(1), this.viewport.get(2), this.viewport.get(3));
        if (depthTest) GL11.glEnable(GL11.GL_DEPTH_TEST);
        if (blending) GL11.glEnable(GL11.GL_BLEND);
    }

    public void dispose() {
        if (this.shader != null) this.shader.dispose();
        if (this.lutShader != null) this.lutShader.dispose();
        if (this.lutFramebuffer != 0) GL30.glDeleteFramebuffers(this.lutFramebuffer);
        if (this.lutTexture != 0) GL11.glDeleteTextures(this.lutTexture);
        if (this.vao != 0) GL30.glDeleteVertexArrays(this.vao);
        this.vao = 0;
    }

    private static final String VERTEX_SOURCE = """
            #version 460 core
            uniform mat4 u_InvProjectionView;
            uniform float u_FarDepth;
            out vec3 v_RayDirection;
            void main() {
                vec2 ndc = vec2((gl_VertexID << 1 & 2) * 2 - 1, (gl_VertexID & 2) * 2 - 1);
                vec4 world = u_InvProjectionView * vec4(ndc, u_FarDepth, 1.0);
                v_RayDirection = world.xyz / max(abs(world.w), 1e-6);
                gl_Position = vec4(ndc, u_FarDepth, 1.0);
            }
            """;

    private static final String LUT_VERTEX_SOURCE = """
            #version 460 core
            out vec2 v_Uv;
            void main() {
                vec2 ndc = vec2((gl_VertexID << 1 & 2) * 2 - 1, (gl_VertexID & 2) * 2 - 1);
                v_Uv = ndc * 0.5 + 0.5;
                gl_Position = vec4(ndc, 0.0, 1.0);
            }
            """;

    private static final String LUT_FRAGMENT_SOURCE = """
            #version 460 core
            in vec2 v_Uv;
            layout(std140, binding = 1) uniform Environment {
                vec4 u_SunDirection;
                vec4 u_MoonDirection;
                vec4 u_SkyLightColor;
                vec4 u_EnvFogColor;
                vec4 u_SkyTint;
            };
            uniform vec3 u_Rayleigh;
            uniform float u_MieStrength;
            uniform float u_MieG;
            uniform vec3 u_NightSky;
            out vec4 fragColor;

            float phaseMie(float mu, float g) {
                float gg = g * g;
                return (1.0 - gg) / max(pow(1.0 + gg - 2.0 * g * mu, 1.5), 0.025);
            }
            void main() {
                float mu = v_Uv.x * 2.0 - 1.0;
                float elevation = v_Uv.y * 2.0 - 1.0;
                float horizon = exp(-max(elevation, 0.0) * 4.5);
                float opticalDepth = 0.22 + 0.78 * horizon;
                float rayleighPhase = 0.75 * (1.0 + mu * mu);
                vec3 rayleigh = u_Rayleigh * rayleighPhase * opticalDepth;
                float mie = u_MieStrength * phaseMie(mu, u_MieG) * opticalDepth;
                float dayVisibility = smoothstep(-0.12, 0.08, u_SunDirection.y);
                vec3 scattered = (rayleigh + vec3(mie)) * u_SkyLightColor.rgb;
                vec3 sky = mix(u_NightSky, scattered, dayVisibility);
                sky = mix(sky, u_EnvFogColor.rgb, horizon * (0.42 + 0.38 * dayVisibility));
                sky *= u_SkyTint.rgb;
                float twilight = (1.0 - smoothstep(0.08, 0.38, abs(u_SunDirection.y)))
                        * smoothstep(-0.16, 0.03, u_SunDirection.y);
                sky += vec3(1.0, 0.18, 0.035) * pow(max(mu, 0.0), 7.0) * twilight * 0.9;
                sky = mix(sky, u_EnvFogColor.rgb * 0.38,
                        1.0 - smoothstep(-0.22, 0.0, elevation));
                fragColor = vec4(max(sky, vec3(0.0)), 1.0);
            }
            """;

    private static final String FRAGMENT_SOURCE = """
            #version 460 core
            in vec3 v_RayDirection;
            layout(std140, binding = 1) uniform Environment {
                vec4 u_SunDirection;
                vec4 u_MoonDirection;
                vec4 u_SkyLightColor;
                vec4 u_EnvFogColor;
                vec4 u_SkyTint;
            };
            uniform sampler2D u_SkyView;
            uniform float u_SunIntensity;
            uniform float u_MoonIntensity;
            uniform float u_DayFraction;
            out vec4 fragColor;

            float hash13(vec3 p) {
                p = fract(p * 0.1031);
                p += dot(p, p.yzx + 33.33);
                return fract((p.x + p.y) * p.z);
            }
            vec2 moonCoordinates(vec3 direction, vec3 moon) {
                vec3 axis = abs(moon.y) > 0.95 ? vec3(0, 0, 1) : vec3(0, 1, 0);
                vec3 right = normalize(cross(axis, moon));
                vec3 up = cross(moon, right);
                return vec2(dot(direction, right), dot(direction, up)) * 210.0;
            }
            void main() {
                vec3 direction = normalize(v_RayDirection);
                vec3 sun = normalize(u_SunDirection.xyz);
                vec3 moon = normalize(u_MoonDirection.xyz);
                float elevation = direction.y;
                float mu = dot(direction, sun);
                vec3 sky = texture(u_SkyView, vec2(mu * 0.5 + 0.5, elevation * 0.5 + 0.5)).rgb;
                float sunDisc = smoothstep(0.999965, 0.999992, mu) * smoothstep(-0.06, 0.01, sun.y);
                float corona = pow(max(mu, 0.0), 480.0) * smoothstep(-0.10, 0.02, sun.y);
                sky += vec3(1.0, 0.83, 0.58) * (sunDisc * u_SunIntensity + corona * 1.7);

                float moonMu = dot(direction, moon);
                float moonDisc = smoothstep(0.99993, 0.999975, moonMu);
                vec2 moonUv = moonCoordinates(direction, moon);
                float phaseAngle = u_MoonDirection.w * 6.2831853;
                float edge = cos(phaseAngle) * sqrt(max(1.0 - moonUv.y * moonUv.y, 0.0));
                float litHalf = cos(phaseAngle) >= 0.0
                        ? smoothstep(edge - 0.08, edge + 0.08, moonUv.x)
                        : 1.0 - smoothstep(edge - 0.08, edge + 0.08, moonUv.x);
                float crater = 0.78 + 0.22 * hash13(floor(vec3(moonUv * 13.0, 7.0)));
                sky += vec3(0.66, 0.72, 0.88) * moonDisc * litHalf * crater
                        * u_MoonIntensity * smoothstep(-0.08, 0.02, moon.y);

                float a = u_DayFraction * 6.2831853;
                vec3 starDir = vec3(cos(a) * direction.x - sin(a) * direction.z, direction.y,
                                    sin(a) * direction.x + cos(a) * direction.z);
                vec3 cell = floor(starDir * 520.0);
                float random = hash13(cell);
                float stars = smoothstep(0.99815, 0.99995, random);
                stars *= 0.45 + 0.55 * hash13(cell + 17.0);
                stars *= smoothstep(-0.08, 0.14, elevation) * u_SkyTint.w * (1.0 - moonDisc);
                sky += vec3(0.72, 0.82, 1.0) * stars;
                fragColor = vec4(max(sky, vec3(0.0)), 1.0);
            }
            """;
}
