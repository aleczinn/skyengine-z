package de.skyengine.graphics.post.passes;

import de.skyengine.graphics.post.PostContext;
import de.skyengine.graphics.post.PostPass;
import de.skyengine.graphics.post.PostProcessor;
import de.skyengine.graphics.post.PostProcessingSettings.AntiAliasingMode;
import de.skyengine.graphics.shader.Shader;
import de.skyengine.graphics.shader.ShaderProgram;
import de.skyengine.graphics.shader.ShaderType;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL30;

/**
 * Generischer Anti-Aliasing-Pass: schaltet über {@link AntiAliasingMode} —
 * <b>NONE</b> = Pass inaktiv (Grading schreibt direkt in den Screen),
 * <b>FXAA</b> = FXAA 3.11 (Quality) auf dem LDR-Grading-Ergebnis.
 *
 * <p><b>TAA/SMAA später als weitere Modi im selben Switch</b> — die Pipeline bleibt dabei
 * unangetastet. Anschlussstellen für TAA (Phase 2): {@code context.history} (LDR-History
 * des letzten Frames), {@code context.velocity} (Bewegungsvektoren; in der statischen
 * Voxelwelt reicht Kamera-Reprojektion aus {@code sceneDepth}), plus Sub-Pixel-Jitter in
 * der Projektionsmatrix (Kamera) — alles Context-/Kamera-Erweiterungen, keine Ketten-Umbauten.
 *
 * <p>Läuft display-referred NACH dem Grading (FXAA erwartet LDR-Luma). Erwartung ehrlich:
 * FXAA glättet Kanten, ersetzt MSAA beim Voxel-Fern-Shimmer nicht vollständig — das
 * erledigt erst TAA.
 */
public final class AntiAliasingPass implements PostPass {

    /* FXAA 3.11 Quality (kompakte Standard-Fassung nach dem Referenz-Algorithmus:
       Kantenerkennung über Luma-Kontrast, Kantenrichtung, iterative Endpunktsuche,
       Kanten- + Subpixel-Blend). */
    private static final String FXAA_FRAGMENT_SHADER = """
            #version 460 core

            in vec2 v_uv;
            out vec4 fragColor;

            uniform sampler2D u_Input;
            uniform vec2 u_InvResolution; // 1/Breite, 1/Hoehe

            const float EDGE_THRESHOLD_MIN = 0.0312;
            const float EDGE_THRESHOLD_MAX = 0.125;
            const float SUBPIXEL_QUALITY = 0.75;
            const int ITERATIONS = 12;
            const float QUALITY[12] = float[](1.0, 1.0, 1.0, 1.0, 1.0, 1.5, 2.0, 2.0, 2.0, 2.0, 4.0, 8.0);

            float lumaOf(vec3 rgb) {
                return dot(rgb, vec3(0.299, 0.587, 0.114));
            }

            void main() {
                vec3 colorCenter = texture(u_Input, v_uv).rgb;
                float lumaCenter = lumaOf(colorCenter);
                float lumaDown  = lumaOf(textureOffset(u_Input, v_uv, ivec2( 0, -1)).rgb);
                float lumaUp    = lumaOf(textureOffset(u_Input, v_uv, ivec2( 0,  1)).rgb);
                float lumaLeft  = lumaOf(textureOffset(u_Input, v_uv, ivec2(-1,  0)).rgb);
                float lumaRight = lumaOf(textureOffset(u_Input, v_uv, ivec2( 1,  0)).rgb);

                float lumaMin = min(lumaCenter, min(min(lumaDown, lumaUp), min(lumaLeft, lumaRight)));
                float lumaMax = max(lumaCenter, max(max(lumaDown, lumaUp), max(lumaLeft, lumaRight)));
                float lumaRange = lumaMax - lumaMin;

                /* Kein Kanten-Kontrast -> unveraendert (frueher Ausstieg) */
                if (lumaRange < max(EDGE_THRESHOLD_MIN, lumaMax * EDGE_THRESHOLD_MAX)) {
                    fragColor = vec4(colorCenter, 1.0);
                    return;
                }

                float lumaDownLeft  = lumaOf(textureOffset(u_Input, v_uv, ivec2(-1, -1)).rgb);
                float lumaUpRight   = lumaOf(textureOffset(u_Input, v_uv, ivec2( 1,  1)).rgb);
                float lumaUpLeft    = lumaOf(textureOffset(u_Input, v_uv, ivec2(-1,  1)).rgb);
                float lumaDownRight = lumaOf(textureOffset(u_Input, v_uv, ivec2( 1, -1)).rgb);

                float lumaDownUp    = lumaDown + lumaUp;
                float lumaLeftRight = lumaLeft + lumaRight;
                float lumaLeftCorners  = lumaDownLeft + lumaUpLeft;
                float lumaDownCorners  = lumaDownLeft + lumaDownRight;
                float lumaRightCorners = lumaDownRight + lumaUpRight;
                float lumaUpCorners    = lumaUpRight + lumaUpLeft;

                float edgeHorizontal = abs(-2.0 * lumaLeft + lumaLeftCorners)
                        + abs(-2.0 * lumaCenter + lumaDownUp) * 2.0
                        + abs(-2.0 * lumaRight + lumaRightCorners);
                float edgeVertical = abs(-2.0 * lumaUp + lumaUpCorners)
                        + abs(-2.0 * lumaCenter + lumaLeftRight) * 2.0
                        + abs(-2.0 * lumaDown + lumaDownCorners);
                bool isHorizontal = edgeHorizontal >= edgeVertical;

                /* Kantenseite bestimmen (steilerer Gradient) */
                float luma1 = isHorizontal ? lumaDown : lumaLeft;
                float luma2 = isHorizontal ? lumaUp : lumaRight;
                float gradient1 = luma1 - lumaCenter;
                float gradient2 = luma2 - lumaCenter;
                bool is1Steepest = abs(gradient1) >= abs(gradient2);
                float gradientScaled = 0.25 * max(abs(gradient1), abs(gradient2));

                float stepLength = isHorizontal ? u_InvResolution.y : u_InvResolution.x;
                float lumaLocalAverage;
                if (is1Steepest) {
                    stepLength = -stepLength;
                    lumaLocalAverage = 0.5 * (luma1 + lumaCenter);
                } else {
                    lumaLocalAverage = 0.5 * (luma2 + lumaCenter);
                }

                vec2 currentUv = v_uv;
                if (isHorizontal) currentUv.y += stepLength * 0.5;
                else currentUv.x += stepLength * 0.5;

                /* Iterative Suche der Kanten-Endpunkte in beide Richtungen */
                vec2 offset = isHorizontal ? vec2(u_InvResolution.x, 0.0) : vec2(0.0, u_InvResolution.y);
                vec2 uv1 = currentUv - offset;
                vec2 uv2 = currentUv + offset;

                float lumaEnd1 = lumaOf(texture(u_Input, uv1).rgb) - lumaLocalAverage;
                float lumaEnd2 = lumaOf(texture(u_Input, uv2).rgb) - lumaLocalAverage;
                bool reached1 = abs(lumaEnd1) >= gradientScaled;
                bool reached2 = abs(lumaEnd2) >= gradientScaled;
                bool reachedBoth = reached1 && reached2;
                if (!reached1) uv1 -= offset;
                if (!reached2) uv2 += offset;

                if (!reachedBoth) {
                    for (int i = 2; i < ITERATIONS; i++) {
                        if (!reached1) lumaEnd1 = lumaOf(texture(u_Input, uv1).rgb) - lumaLocalAverage;
                        if (!reached2) lumaEnd2 = lumaOf(texture(u_Input, uv2).rgb) - lumaLocalAverage;
                        reached1 = abs(lumaEnd1) >= gradientScaled;
                        reached2 = abs(lumaEnd2) >= gradientScaled;
                        reachedBoth = reached1 && reached2;
                        if (!reached1) uv1 -= offset * QUALITY[i];
                        if (!reached2) uv2 += offset * QUALITY[i];
                        if (reachedBoth) break;
                    }
                }

                float distance1 = isHorizontal ? (v_uv.x - uv1.x) : (v_uv.y - uv1.y);
                float distance2 = isHorizontal ? (uv2.x - v_uv.x) : (uv2.y - v_uv.y);
                bool isDirection1 = distance1 < distance2;
                float distanceFinal = min(distance1, distance2);
                float edgeThickness = distance1 + distance2;
                float pixelOffset = -distanceFinal / edgeThickness + 0.5;

                /* Nur verschieben, wenn der Endpunkt-Luma zur Kantenseite passt */
                bool isLumaCenterSmaller = lumaCenter < lumaLocalAverage;
                bool correctVariation = ((isDirection1 ? lumaEnd1 : lumaEnd2) < 0.0) != isLumaCenterSmaller;
                float finalOffset = correctVariation ? pixelOffset : 0.0;

                /* Subpixel-Aliasing (isolierte Pixel/duenne Linien) */
                float lumaAverage = (1.0 / 12.0) * (2.0 * (lumaDownUp + lumaLeftRight) + lumaLeftCorners + lumaRightCorners);
                float subPixelOffset1 = clamp(abs(lumaAverage - lumaCenter) / lumaRange, 0.0, 1.0);
                float subPixelOffset2 = (-2.0 * subPixelOffset1 + 3.0) * subPixelOffset1 * subPixelOffset1;
                float subPixelOffsetFinal = subPixelOffset2 * subPixelOffset2 * SUBPIXEL_QUALITY;
                finalOffset = max(finalOffset, subPixelOffsetFinal);

                vec2 finalUv = v_uv;
                if (isHorizontal) finalUv.y += finalOffset * stepLength;
                else finalUv.x += finalOffset * stepLength;

                fragColor = vec4(texture(u_Input, finalUv).rgb, 1.0);
            }
            """;

    private ShaderProgram fxaaProgram;

    @Override
    public void init(PostContext context) {
        this.fxaaProgram = new ShaderProgram(
                new Shader(PostProcessor.FULLSCREEN_VERTEX_SHADER, ShaderType.VERTEX),
                new Shader(FXAA_FRAGMENT_SHADER, ShaderType.FRAGMENT));
        this.fxaaProgram.bind();
        this.fxaaProgram.setUniformi("u_Input", 0);
        this.fxaaProgram.unbind();
    }

    @Override
    public boolean isActive(PostContext context) {
        return context.settings.getAaMode() != AntiAliasingMode.NONE;
    }

    @Override
    public void execute(PostContext context) {
        /* Aktuell einziger Modus neben NONE: FXAA. TAA/SMAA docken hier als weitere
           Zweige an (s. Klassen-Javadoc), ohne die Kette zu aendern. */
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, context.targetFbo);
        this.fxaaProgram.bind();
        this.fxaaProgram.setUniformVector2f("u_InvResolution", 1F / context.width, 1F / context.height);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, context.input);
        context.drawFullscreenTriangle();
        this.fxaaProgram.unbind();
    }

    @Override
    public void dispose() {
        if (this.fxaaProgram != null) this.fxaaProgram.dispose();
    }
}
