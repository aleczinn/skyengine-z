package de.skyengine.graphics.post.passes;

import de.skyengine.core.settings.GameSettings;
import de.skyengine.graphics.post.PostContext;
import de.skyengine.graphics.post.PostPass;
import de.skyengine.graphics.post.PostProcessor;
import de.skyengine.graphics.shader.Shader;
import de.skyengine.graphics.shader.ShaderProgram;
import de.skyengine.graphics.shader.ShaderType;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL30;

import java.nio.ByteBuffer;

/**
 * Halbaufgeloestes, tiefenbasiertes AO nur fuer den Fernbereich. L0 behaelt sein gebackenes
 * Corner-AO; dadurch kostet der Volumenpfad weiterhin nur acht Byte je Basisquad.
 */
public final class LodSsaoPass implements PostPass {

    private static final String AO_FRAGMENT = """
            #version 460 core
            in vec2 v_uv;
            layout(location = 0) out float fragAo;

            uniform sampler2D u_Depth;
            uniform sampler2D u_LodMask;
            uniform mat4 u_InvProjView;
            uniform vec2 u_InvResolution;
            uniform int u_ReversedDepth;
            uniform int u_Samples;
            uniform int u_Downsample;

            const float GOLDEN_ANGLE = 2.39996323;

            bool clearDepth(float depth) {
                return u_ReversedDepth != 0 ? depth <= 0.0000001 : depth >= 0.9999999;
            }

            vec3 reconstruct(vec2 uv, float depth) {
                float clipZ = u_ReversedDepth != 0 ? depth : depth * 2.0 - 1.0;
                vec4 rel = u_InvProjView * vec4(uv * 2.0 - 1.0, clipZ, 1.0);
                return rel.xyz / rel.w;
            }

            bool validUv(vec2 uv) {
                return all(greaterThanEqual(uv, vec2(0.0)))
                        && all(lessThanEqual(uv, vec2(1.0)));
            }

            bool samplePosition(vec2 uv, out vec3 position) {
                if (!validUv(uv)) return false;
                if (texture(u_LodMask, uv).r < 0.5) return false;
                float depth = texture(u_Depth, uv).r;
                if (clearDepth(depth)) return false;
                position = reconstruct(uv, depth);
                return true;
            }

            float hash12(vec2 p) {
                vec3 p3 = fract(vec3(p.xyx) * 0.1031);
                p3 += dot(p3, p3.yzx + 33.33);
                return fract((p3.x + p3.y) * p3.z);
            }

            void main() {
                if (texture(u_LodMask, v_uv).r < 0.5) { fragAo = 1.0; return; }
                float depth = texture(u_Depth, v_uv).r;
                if (clearDepth(depth)) { fragAo = 1.0; return; }
                vec3 p = reconstruct(v_uv, depth);

                vec2 stepUv = u_InvResolution * float(u_Downsample);
                vec3 left, right, down, up;
                bool hasLeft = samplePosition(v_uv - vec2(stepUv.x, 0.0), left);
                bool hasRight = samplePosition(v_uv + vec2(stepUv.x, 0.0), right);
                bool hasDown = samplePosition(v_uv - vec2(0.0, stepUv.y), down);
                bool hasUp = samplePosition(v_uv + vec2(0.0, stepUv.y), up);
                if ((!hasLeft && !hasRight) || (!hasDown && !hasUp)) {
                    fragAo = 1.0; return;
                }
                vec3 tangentX = hasRight && (!hasLeft || length(right - p) <= length(left - p))
                        ? right - p : p - left;
                vec3 tangentY = hasUp && (!hasDown || length(up - p) <= length(down - p))
                        ? up - p : p - down;
                vec3 normal = normalize(cross(tangentX, tangentY));
                if (dot(normal, -p) < 0.0) normal = -normal;

                float rotation = hash12(gl_FragCoord.xy) * 6.28318531;
                float radiusWorld = clamp(1.5 + length(p) * 0.015, 1.5, 48.0);
                float occlusion = 0.0;
                for (int i = 0; i < 16; i++) {
                    if (i >= u_Samples) break;
                    float t = (float(i) + 0.75) / float(u_Samples);
                    float angle = rotation + float(i) * GOLDEN_ANGLE;
                    vec2 offset = vec2(cos(angle), sin(angle)) * (2.0 + 6.0 * t)
                            * u_InvResolution;
                    vec2 sampleUv = v_uv + offset;
                    if (!validUv(sampleUv)) continue;
                    if (texture(u_LodMask, sampleUv).r < 0.5) continue;
                    float sampleDepth = texture(u_Depth, sampleUv).r;
                    if (clearDepth(sampleDepth)) continue;
                    vec3 delta = reconstruct(sampleUv, sampleDepth) - p;
                    float distanceToSample = length(delta);
                    float hemisphere = smoothstep(0.04, 0.35, dot(normal, delta / max(distanceToSample, 0.0001)));
                    float range = 1.0 - smoothstep(radiusWorld * 0.15, radiusWorld, distanceToSample);
                    occlusion += hemisphere * range;
                }
                fragAo = clamp(1.0 - occlusion / float(max(u_Samples, 1)) * 0.90,
                               0.70, 1.0);
            }
            """;

    private static final String COMPOSITE_FRAGMENT = """
            #version 460 core
            in vec2 v_uv;
            out vec4 fragColor;
            uniform sampler2D u_Input;
            uniform sampler2D u_Ao;
            uniform sampler2D u_LodMask;
            void main() {
                vec4 color = texture(u_Input, v_uv);
                float mask = texture(u_LodMask, v_uv).r;
                float ao = mask >= 0.5 ? texture(u_Ao, v_uv).r : 1.0;
                fragColor = vec4(color.rgb * mix(1.0, ao, 0.30), color.a);
            }
            """;

    private ShaderProgram aoProgram, compositeProgram;
    private int aoTexture, aoFbo;
    private int width, height;

    @Override
    public void init(PostContext context) {
        this.aoProgram = program(AO_FRAGMENT);
        this.aoProgram.bind();
        this.aoProgram.setUniformi("u_Depth", 0);
        this.aoProgram.setUniformi("u_LodMask", 1);
        this.aoProgram.unbind();
        this.compositeProgram = program(COMPOSITE_FRAGMENT);
        this.compositeProgram.bind();
        this.compositeProgram.setUniformi("u_Input", 0);
        this.compositeProgram.setUniformi("u_Ao", 1);
        this.compositeProgram.setUniformi("u_LodMask", 2);
        this.compositeProgram.unbind();
        this.createTargets(context.width, context.height, targetDivisor(context));
    }

    private static ShaderProgram program(String fragment) {
        return new ShaderProgram(new Shader(PostProcessor.FULLSCREEN_VERTEX_SHADER, ShaderType.VERTEX),
                new Shader(fragment, ShaderType.FRAGMENT));
    }

    @Override
    public boolean isActive(PostContext context) {
        GameSettings settings = GameSettings.get();
        if (!settings.lodEnabled || !settings.ambientOcclusion
                || settings.lodMaxDistance <= settings.renderDistance
                || context.sceneDepth == 0 || context.sceneLodMask == 0) return false;
        GameSettings.ScreenSpaceAoQuality quality = settings.screenSpaceAoQuality;
        return quality != GameSettings.ScreenSpaceAoQuality.OFF;
    }

    @Override
    public void execute(PostContext context) {
        GameSettings settings = GameSettings.get();
        int samples = settings.screenSpaceAoQuality == GameSettings.ScreenSpaceAoQuality.AUTO
                ? GameSettings.ScreenSpaceAoQuality.AUTO.samples : settings.screenSpaceAoQuality.samples;
        int divisor = targetDivisor(context);
        int wantedWidth = Math.max(1, (context.width + divisor - 1) / divisor);
        int wantedHeight = Math.max(1, (context.height + divisor - 1) / divisor);
        if (wantedWidth != this.width || wantedHeight != this.height) {
            this.disposeTargets();
            this.createTargets(context.width, context.height, divisor);
        }

        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, this.aoFbo);
        GL11.glViewport(0, 0, this.width, this.height);
        this.aoProgram.bind();
        this.aoProgram.setUniformMatrix4f("u_InvProjView", context.invProjView);
        this.aoProgram.setUniformVector2f("u_InvResolution", 1F / context.width, 1F / context.height);
        this.aoProgram.setUniformi("u_ReversedDepth", context.reversedDepth ? 1 : 0);
        this.aoProgram.setUniformi("u_Samples", samples);
        this.aoProgram.setUniformi("u_Downsample", divisor);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, context.sceneDepth);
        GL13.glActiveTexture(GL13.GL_TEXTURE1);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, context.sceneLodMask);
        context.drawFullscreenTriangle();
        this.aoProgram.unbind();

        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, context.targetFbo);
        GL11.glViewport(0, 0, context.width, context.height);
        this.compositeProgram.bind();
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, context.input);
        GL13.glActiveTexture(GL13.GL_TEXTURE1);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, this.aoTexture);
        GL13.glActiveTexture(GL13.GL_TEXTURE2);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, context.sceneLodMask);
        context.drawFullscreenTriangle();
        this.compositeProgram.unbind();
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
    }

    @Override
    public void resize(PostContext context) {
        this.disposeTargets();
        this.createTargets(context.width, context.height, targetDivisor(context));
    }

    static int targetDivisor(PostContext context) {
        GameSettings.ScreenSpaceAoQuality quality = GameSettings.get().screenSpaceAoQuality;
        return targetDivisor(quality, context.width, context.height);
    }

    static int targetDivisor(GameSettings.ScreenSpaceAoQuality quality, int width, int height) {
        if (quality == GameSettings.ScreenSpaceAoQuality.BASIC) return 4;
        if (quality == GameSettings.ScreenSpaceAoQuality.AUTO
                && (long) width * height >= 2_000_000L) return 4;
        return 2;
    }

    private void createTargets(int fullWidth, int fullHeight, int divisor) {
        this.width = Math.max(1, (fullWidth + divisor - 1) / divisor);
        this.height = Math.max(1, (fullHeight + divisor - 1) / divisor);
        this.aoTexture = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, this.aoTexture);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_R8, this.width, this.height, 0,
                GL11.GL_RED, GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        this.aoFbo = GL30.glGenFramebuffers();
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, this.aoFbo);
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0,
                GL11.GL_TEXTURE_2D, this.aoTexture, 0);
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
    }

    private void disposeTargets() {
        if (this.aoFbo != 0) GL30.glDeleteFramebuffers(this.aoFbo);
        if (this.aoTexture != 0) GL11.glDeleteTextures(this.aoTexture);
        this.aoFbo = 0;
        this.aoTexture = 0;
    }

    @Override
    public void dispose() {
        this.disposeTargets();
        if (this.aoProgram != null) this.aoProgram.dispose();
        if (this.compositeProgram != null) this.compositeProgram.dispose();
    }
}
