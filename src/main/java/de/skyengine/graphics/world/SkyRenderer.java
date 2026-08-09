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

            const float PI = 3.141592653589793;
            const float PLANET_RADIUS = 6371000.0;
            const float ATMOSPHERE_RADIUS = PLANET_RADIUS + 110000.0;
            const vec2 SCALE_HEIGHT = vec2(8400.0, 1250.0);
            const vec3 RAYLEIGH_COEFFICIENT = vec3(8.059375432e-6, 1.671209429e-5, 4.080133294e-5);
            const vec3 MIE_COEFFICIENT = vec3(1.666442358e-6, 1.812685127e-6, 1.958927896e-6);
            const vec3 OZONE_COEFFICIENT = vec3(8.304280072e-7, 1.314911970e-6, 5.440679729e-8);
            const vec3 SUNLIGHT_COLOR = vec3(1.051, 0.985, 0.940);

            float raySphereExit(vec3 origin, vec3 direction, float radius) {
                float b = dot(origin, direction);
                float c = dot(origin, origin) - radius * radius;
                return -b + sqrt(max(b * b - c, 0.0));
            }
            vec3 atmosphereDensity(float altitude) {
                vec2 rayleighMie = exp(-max(altitude, 0.0) / SCALE_HEIGHT);
                float altitudeKm = altitude * 0.001;
                float o1 = 12.5 * exp(-altitudeKm / 8.0);
                float o2 = 30.0 * exp((18.0 - altitudeKm) * (altitudeKm - 18.0) / 80.0);
                float o3 = 75.0 * exp((23.5 - altitudeKm) * (altitudeKm - 23.5) / 50.0);
                float o4 = 50.0 * exp((30.0 - altitudeKm) * (altitudeKm - 30.0) / 150.0);
                return vec3(rayleighMie, 0.007428 * (o1 + o2 + o3 + o4));
            }
            vec3 extinction(vec3 density) {
                return RAYLEIGH_COEFFICIENT * u_Rayleigh * density.x
                        + MIE_COEFFICIENT * u_MieStrength * density.y
                        + OZONE_COEFFICIENT * density.z;
            }
            vec3 lightTransmittance(vec3 origin, vec3 lightDirection) {
                float groundProjection = dot(origin, lightDirection);
                float groundDiscriminant = groundProjection * groundProjection
                        - (dot(origin, origin) - PLANET_RADIUS * PLANET_RADIUS);
                if (groundProjection < 0.0 && groundDiscriminant >= 0.0) return vec3(0.0);
                float distanceToSpace = raySphereExit(origin, lightDirection, ATMOSPHERE_RADIUS);
                vec3 opticalDepth = vec3(0.0);
                const int LIGHT_STEPS = 6;
                float stepLength = distanceToSpace / float(LIGHT_STEPS);
                for (int i = 0; i < LIGHT_STEPS; ++i) {
                    vec3 samplePosition = origin + lightDirection * (float(i) + 0.5) * stepLength;
                    opticalDepth += extinction(atmosphereDensity(length(samplePosition) - PLANET_RADIUS))
                            * stepLength;
                }
                return exp(-opticalDepth);
            }
            float phaseMie(float mu, float g) {
                float gg = g * g;
                return (1.0 - gg) / (4.0 * PI
                        * max(pow(1.0 + gg - 2.0 * g * mu, 1.5), 1e-4));
            }
            void main() {
                float mu = v_Uv.x * 2.0 - 1.0;
                float elevation = v_Uv.y * 2.0 - 1.0;
                vec3 sun = normalize(u_SunDirection.xyz);
                float horizontal = sqrt(max(1.0 - elevation * elevation, 0.0));
                float sunHorizontal = length(sun.xz);
                float cosAzimuth = sunHorizontal > 1e-5 && horizontal > 1e-5
                        ? clamp((mu - elevation * sun.y) / (horizontal * sunHorizontal), -1.0, 1.0)
                        : 1.0;
                vec3 ray = vec3(horizontal * cosAzimuth, elevation,
                        horizontal * sqrt(max(1.0 - cosAzimuth * cosAzimuth, 0.0)));
                ray = normalize(ray);
                mu = dot(ray, sun);

                vec3 origin = vec3(0.0, PLANET_RADIUS + 2.0, 0.0);
                float rayLength = raySphereExit(origin, ray, ATMOSPHERE_RADIUS);
                vec3 opticalDepth = vec3(0.0);
                vec3 scattering = vec3(0.0);
                const int VIEW_STEPS = 12;
                float stepLength = rayLength / float(VIEW_STEPS);
                float rayleighPhase = 3.0 * (1.0 + mu * mu) / (16.0 * PI);
                float miePhase = phaseMie(mu, u_MieG);
                for (int i = 0; i < VIEW_STEPS; ++i) {
                    vec3 samplePosition = origin + ray * (float(i) + 0.5) * stepLength;
                    vec3 density = atmosphereDensity(length(samplePosition) - PLANET_RADIUS);
                    opticalDepth += extinction(density) * stepLength;
                    vec3 viewTransmittance = exp(-opticalDepth);
                    vec3 sunTransmittance = lightTransmittance(samplePosition, sun);
                    vec3 localScattering = RAYLEIGH_COEFFICIENT * u_Rayleigh
                                    * density.x * rayleighPhase
                            + MIE_COEFFICIENT * (0.9 * u_MieStrength)
                                    * density.y * miePhase;
                    scattering += viewTransmittance * sunTransmittance
                            * localScattering * stepLength;
                }

                vec3 physicalSky = scattering * SUNLIGHT_COLOR * 22.0;
                float skyLuminance = dot(physicalSky, vec3(0.2126, 0.7152, 0.0722));
                physicalSky = mix(vec3(skyLuminance), physicalSky, 0.52);
                float night = 1.0 - smoothstep(-0.18, 0.06, sun.y);
                float zenith = pow(clamp(elevation, 0.0, 1.0), 0.35);
                vec3 nightSky = mix(u_NightSky * 1.55, u_NightSky, zenith);
                // Photon keeps a desaturated aerosol veil above distant terrain even at
                // midnight. This is view-height dependent, not a brighter blue horizon.
                float nightHorizon = exp(-max(elevation, 0.0) * 4.8);
                vec3 nightHaze = vec3(0.115, 0.126, 0.148);
                nightSky = mix(nightSky, nightHaze, nightHorizon * 0.72);
                float dayVisibility = smoothstep(-0.12, 0.04, sun.y);
                float noon = smoothstep(0.35, 0.82, sun.y);

                // Photon's noon look is dominated by multiple scattering and exposure. The
                // compact LUT has no 3D multiple-scattering volume, so use its characteristic
                // neutral horizon as the missing high-order term instead of leaving the yellow
                // single-scattering result visible.
                vec3 noonZenith = vec3(0.27, 0.42, 0.62);
                vec3 noonHorizon = vec3(0.79, 0.84, 0.88);
                float horizonWeight = exp(-max(elevation, 0.0) * 3.4);
                vec3 photonDay = mix(noonZenith, noonHorizon, horizonWeight);
                vec3 sky = mix(physicalSky, photonDay, noon * 0.92);

                float twilight = (1.0 - smoothstep(0.02, 0.34, abs(sun.y)))
                        * smoothstep(-0.17, 0.035, sun.y);
                float sunsetBand = exp(-abs(elevation) * 3.2);
                float sunsetForward = 0.12 + 0.88 * pow(max(mu, 0.0), 3.0);
                vec3 sunsetColor = mix(vec3(0.48, 0.30, 0.43), vec3(1.0, 0.34, 0.055),
                        sunsetForward);
                sky = mix(sky, sunsetColor, twilight * sunsetBand * 0.74);
                sky = mix(sky, nightSky, night);

                float belowHorizon = 1.0 - smoothstep(-0.18, 0.015, elevation);
                sky = mix(sky, u_EnvFogColor.rgb * mix(0.10, 0.50, dayVisibility),
                        belowHorizon);
                sky *= u_SkyTint.rgb;
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

            const float TAU = 6.283185307179586;
            const float SUN_RADIUS = radians(2.0);
            const float MOON_RADIUS = radians(3.0);

            vec3 hash33(vec3 p) {
                p = fract(p * vec3(0.1031, 0.1030, 0.0973));
                p += dot(p, p.yxz + 33.33);
                return fract((p.xxy + p.yxx) * p.zyx);
            }
            float valueNoise(vec2 coordinate, float seed) {
                vec2 cell = floor(coordinate);
                vec2 fraction = fract(coordinate);
                fraction = fraction * fraction * (3.0 - 2.0 * fraction);
                float a = hash33(vec3(cell, seed)).x;
                float b = hash33(vec3(cell + vec2(1, 0), seed)).x;
                float c = hash33(vec3(cell + vec2(0, 1), seed)).x;
                float d = hash33(vec3(cell + vec2(1, 1), seed)).x;
                return mix(mix(a, b, fraction.x), mix(c, d, fraction.x), fraction.y);
            }
            vec2 moonCoordinates(vec3 direction, vec3 moon) {
                vec3 axis = abs(moon.y) > 0.95 ? vec3(0, 0, 1) : vec3(0, 1, 0);
                vec3 right = normalize(cross(axis, moon));
                vec3 up = cross(moon, right);
                return vec2(dot(direction, right), dot(direction, up)) / tan(MOON_RADIUS);
            }
            vec3 blackbody(float temperature) {
                float t = temperature / 100.0;
                float r = t <= 66.0 ? 1.0
                        : clamp(1.292936 * pow(t - 60.0, -0.133205), 0.0, 1.0);
                float g = t <= 66.0
                        ? clamp(0.390082 * log(max(t, 1.0)) - 0.631841, 0.0, 1.0)
                        : clamp(1.129891 * pow(t - 60.0, -0.075515), 0.0, 1.0);
                float b = t >= 66.0 ? 1.0
                        : (t <= 19.0 ? 0.0
                        : clamp(0.543207 * log(t - 10.0) - 1.196254, 0.0, 1.0));
                return vec3(r, g, b);
            }
            vec3 unstableStarField(vec2 coordinate, float threshold) {
                vec3 noise = hash33(vec3(coordinate, 19.7));
                float star = smoothstep(threshold, 1.0, noise.x);
                star *= star; star *= star; star *= star; star *= star;
                float temperature = mix(4500.0, 8500.0, noise.y);
                float twinkle = 1.0 - noise.z
                        * cos(u_DayFraction * TAU * 48.0 + noise.y * TAU);
                return star * twinkle * blackbody(temperature);
            }
            vec3 stableStarField(vec2 coordinate, float threshold) {
                coordinate = abs(coordinate) + 33.3 * step(vec2(0.0), coordinate);
                vec2 cell = floor(coordinate);
                vec2 fraction = fract(coordinate);
                fraction = fraction * fraction * (3.0 - 2.0 * fraction);
                return mix(mix(unstableStarField(cell, threshold),
                               unstableStarField(cell + vec2(1, 0), threshold), fraction.x),
                           mix(unstableStarField(cell + vec2(0, 1), threshold),
                               unstableStarField(cell + vec2(1, 1), threshold), fraction.x),
                           fraction.y);
            }
            void main() {
                vec3 direction = normalize(v_RayDirection);
                vec3 sun = normalize(u_SunDirection.xyz);
                vec3 moon = normalize(u_MoonDirection.xyz);
                float elevation = direction.y;
                float mu = dot(direction, sun);
                vec3 sky = texture(u_SkyView, vec2(mu * 0.5 + 0.5, elevation * 0.5 + 0.5)).rgb;
                float sunDistance = acos(clamp(mu, -1.0, 1.0));
                float sunDisc = 1.0 - smoothstep(SUN_RADIUS * 0.96, SUN_RADIUS, sunDistance);
                float limb = sqrt(clamp(1.0 - sunDistance / SUN_RADIUS, 0.0, 1.0));
                float sunRadiusUnits = sunDistance / SUN_RADIUS;
                float innerGlow = exp(-sunRadiusUnits * sunRadiusUnits * 0.20);
                float outerGlow = exp(-sunRadiusUnits * 0.42);
                float sunVisibility = smoothstep(-0.10, 0.02, sun.y);
                vec3 sunColor = vec3(1.051, 0.985, 0.940);
                sky += sunColor * sunVisibility
                        * (innerGlow * 0.42 + outerGlow * 0.13);
                sky += sunColor * sunDisc * mix(0.62, 0.92, limb) * u_SunIntensity;

                float moonMu = dot(direction, moon);
                float moonDistance = acos(clamp(moonMu, -1.0, 1.0));
                float moonDisc = 1.0 - smoothstep(MOON_RADIUS * 0.98, MOON_RADIUS, moonDistance);
                vec2 moonUv = moonCoordinates(direction, moon);
                float sphereZ = sqrt(max(1.0 - dot(moonUv, moonUv), 0.0));
                vec3 moonNormal = normalize(vec3(moonUv, sphereZ));
                float phaseAngle = u_MoonDirection.w * TAU;
                // Vanilla/Photon phase 0 is a full moon, phase 4 a new moon.
                vec3 moonLight = normalize(vec3(sin(phaseAngle), 0.12, cos(phaseAngle)));
                float moonShadow = smoothstep(-0.04, 0.12, dot(moonNormal, moonLight));
                float coarse = valueNoise(moonUv * 8.0 + 17.0, 4.0);
                float fine = valueNoise(moonUv * 25.0 - 9.0, 11.0);
                float maria = valueNoise(moonUv * 4.0 + vec2(3.0, -7.0), 23.0);
                float moonTexture = clamp(0.54 + 0.32 * coarse
                        + 0.14 * fine - 0.18 * smoothstep(0.62, 0.82, maria), 0.26, 1.0);
                float edgeGlow = pow(clamp(length(moonUv), 0.0, 1.0), 8.0);
                vec3 litMoon = vec3(1.34, 1.41, 1.52) * moonShadow
                        * (1.0 + 0.32 * edgeGlow);
                vec3 darkMoon = vec3(0.020, 0.030, 0.050) * (0.55 + 0.45 * edgeGlow);
                vec3 moonColor = max(litMoon, darkMoon) * moonTexture * u_MoonIntensity;
                float moonVisible = moonDisc * smoothstep(-0.08, 0.02, moon.y);
                sky = mix(sky, moonColor, moonVisible);
                float moonRadiusUnits = moonDistance / MOON_RADIUS;
                // Narrow atmospheric aureole; the broad HDR halo is produced by BloomPass.
                float moonHalo = (0.18 * exp(-moonRadiusUnits * moonRadiusUnits * 0.10)
                        + 0.045 * exp(-moonRadiusUnits * 0.22))
                        * smoothstep(-0.08, 0.02, moon.y) * (1.0 - moonDisc);
                sky += vec3(0.58, 0.67, 0.84) * moonHalo * u_MoonIntensity;

                float a = u_DayFraction * 6.2831853;
                vec3 starDir = vec3(cos(a) * direction.x - sin(a) * direction.z, direction.y,
                                    sin(a) * direction.x + cos(a) * direction.z);
                vec2 starCoordinate = starDir.xy
                        / (abs(starDir.z) + length(starDir.xy))
                        + 41.21 * sign(starDir.z);
                starCoordinate *= 600.0;
                float starThreshold = 1.0
                        - 0.025 * smoothstep(-0.2, 0.05, -sun.y);
                vec3 stars = stableStarField(starCoordinate, starThreshold);
                stars *= smoothstep(-0.10, 0.10, elevation) * u_SkyTint.w
                        * (1.0 - moonDisc);
                // A squared core retains sub-pixel definition under TAA and stays below the
                // bloom threshold, matching Photon's crisp stars instead of fuzzy dots.
                sky += stars * stars * 0.34;
                fragColor = vec4(max(sky, vec3(0.0)), 1.0);
            }
            """;
}
