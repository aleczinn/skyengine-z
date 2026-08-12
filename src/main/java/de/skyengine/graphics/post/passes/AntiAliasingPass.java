package de.skyengine.graphics.post.passes;

import de.skyengine.core.SkyEngine;
import de.skyengine.graphics.post.PostContext;
import de.skyengine.graphics.post.PostPass;
import de.skyengine.graphics.post.PostProcessor;
import de.skyengine.graphics.post.PostProcessingSettings.AntiAliasingMode;
import de.skyengine.graphics.shader.Shader;
import de.skyengine.graphics.shader.ShaderProgram;
import de.skyengine.graphics.shader.ShaderType;
import de.skyengine.utils.logging.LogManager;
import de.skyengine.utils.logging.Logger;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL30;

import java.nio.ByteBuffer;

/**
 * Generischer Anti-Aliasing-Pass: schaltet über {@link AntiAliasingMode} —
 * <b>NONE</b> = Pass inaktiv (Grading schreibt direkt in den GuiScreen),
 * <b>FXAA</b> = FXAA 3.11 (Quality) auf dem LDR-Grading-Ergebnis,
 * <b>TAA</b> = zeitliche Akkumulation (der eigentliche MSAA-Ersatz fürs Voxel-Fern-Shimmer).
 *
 * <p><b>TAA-Ablauf:</b> Kamera jittert die Projektion subpixelweise (Halton, s. Camera/
 * PostProcessor); der Resolve rekonstruiert pro Pixel aus {@code sceneDepth} die
 * KAMERARELATIVE Position (View hat keine Translation — Reprojektion bleibt komplett in
 * Floats), projiziert sie mit der ungejitterten Vorframe-PV + camDelta in die History und
 * blendet sie — geclampt auf die 3×3-Farb-AABB des aktuellen Frames (Ghosting-Schutz;
 * ersetzt den Velocity-Buffer, {@code context.velocity} bleibt der Anschluss für
 * per-Objekt-Motion). Resolve schreibt in die History (Ping-Pong, RGBA16F), ein
 * Copy-Schritt bringt sie zurück in die HDR-Kette. FXAA und CAS laufen in einer zweiten
 * Instanz erst nach Bloom und Film-Transform; CAS wirkt nie in die History.
 *
 * <p><b>Voraussetzung:</b> {@code sceneDepth} existiert nur bei msaaSamples=0 — bei
 * TAA + MSAA fällt die temporale Stufe mit Log-Hinweis auf Copy zurück. SMAA wäre ein weiterer
 * Modus im selben Switch.
 *
 * <p>Die TEMPORAL-Stufe läuft HDR vor Bloom, die FINAL-Stufe display-referred nach Grading.
 */
public final class AntiAliasingPass implements PostPass {

    public enum Stage { TEMPORAL, FINAL }

    /* FXAA 3.11 Quality (kompakte Standard-Fassung nach dem Referenz-Algorithmus:
       Kantenerkennung über Luma-Kontrast, Kantenrichtung, iterative Endpunktsuche,
       Kanten- + Subpixel-Blend). */
    private static final String FXAA_FRAGMENT_SHADER = """
            #version 460 core

            in vec2 v_uv;
            out vec4 fragColor;

            uniform sampler2D u_Input;
            uniform vec2 u_InvResolution; // 1/Breite, 1/Hoehe
            /* 1.0 = volles FXAA in der finalen display-referred Stufe. */
            uniform float u_SubpixelScale;

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
                float subPixelOffsetFinal = subPixelOffset2 * subPixelOffset2 * SUBPIXEL_QUALITY * u_SubpixelScale;
                finalOffset = max(finalOffset, subPixelOffsetFinal);

                vec2 finalUv = v_uv;
                if (isHorizontal) finalUv.y += finalOffset * stepLength;
                else finalUv.x += finalOffset * stepLength;

                fragColor = vec4(texture(u_Input, finalUv).rgb, 1.0);
            }
            """;

    /* TAA-Resolve, portiert auf das Verhalten von BSL Shaders v10 (Capt Tatsu, taa.glsl —
       vom User als Referenz geliefert): YCoCg-ClipAABB statt RGB-Clamp (erhaelt Detail,
       v.a. Laub), Catmull-Rom c=0.7/5-Tap normalisiert (schaerfer als Standard-c=0.5),
       Blend exp(-velocity)*0.2 + (weight-0.2). Unsere kamerarelative Reprojektion bleibt
       (aequivalent zu BSLs Chocapic-Reprojection). Reversed-Z + ClipControl ZERO_TO_ONE:
       Depth-Sample d IST Clip-z in [0,1] (kein z*2-1!), d=0 = fern (Clear).
       LEHREN (nicht rueckbauen): (1) Current NIE resampeln — jede Filterung (auch CR)
       frisst die Frische des aktuellen Frames; die frueher versuchte Jitter-Kompensation
       war deshalb ein Fehler. (2) History NIE bilinear sampeln (IIR-Resample-Blur). */
    private static final String TAA_FRAGMENT_SHADER = """
            #version 460 core

            in vec2 v_uv;
            out vec4 fragColor;

            uniform sampler2D u_Current;    // LDR nach Grading, FXAA-vorgeglaettet (BSL-Kette)
            uniform sampler2D u_History;    // Read-Seite des Ping-Pongs (Vorframe-Resolve)
            uniform sampler2D u_Depth;      // Szene-Depth (32F, Reversed-Z)
            uniform sampler2D u_HandDepth;  // Szene-Depth direkt nach dem Hand-Pass
            uniform mat4 u_InvProjView;     // Inverse der UNGEJITTERTEN PV (Photon-Reprojektion)
            uniform mat4 u_PrevProjView;    // UNGEJITTERTE PV des Vorframes
            uniform vec3 u_CamDelta;        // camNow - camPrev: P_relPrev = P_relNow + delta
            uniform int u_HistoryValid;     // 0 = erster Frame nach Reset -> nur aktuell
            uniform int u_InverseDepth;

            vec3 rgbToYCoCg(vec3 col) {
                return vec3(
                    col.r *  0.25 + col.g * 0.5 + col.b *  0.25,
                    col.r *  0.5                - col.b *  0.5,
                    col.r * -0.25 + col.g * 0.5 + col.b * -0.25);
            }

            vec3 yCoCgToRgb(vec3 col) {
                float n = col.r - col.b;
                return vec3(n + col.g, col.r + col.b, n - col.g);
            }

            /* Richtungs-Clip zur AABB-Mitte (BSL ClipAABB) — erhaelt im Gegensatz zum
               harten clamp() die Farbrelation der History (weniger Detail-Abflachung). */
            vec3 clipAabb(vec3 q, vec3 aabbMin, vec3 aabbMax) {
                vec3 pClip = 0.5 * (aabbMax + aabbMin);
                vec3 eClip = 0.5 * (aabbMax - aabbMin);
                vec3 vClip = q - pClip;
                vec3 vUnit = vClip / max(eClip, vec3(1.0e-3));
                vec3 aUnit = abs(vUnit);
                float maUnit = max(aUnit.x, max(aUnit.y, aUnit.z));
                return maUnit > 1.0 ? pClip + vClip / maUnit : q;
            }

            /* Catmull-Rom c=0.7, 5 Taps, gewichts-normalisiert (BSL textureCatmullRom):
               schaerferer Kernel als Standard-c=0.5, Eck-Taps entfallen. */
            vec3 sampleHistoryCatmullRom(vec2 uv, vec2 texSize) {
                vec2 position = uv * texSize;
                vec2 centerPosition = floor(position - 0.5) + 0.5;
                vec2 f = position - centerPosition;
                vec2 f2 = f * f;
                vec2 f3 = f * f2;

                const float c = 0.6;
                vec2 w0 =        -c  * f3 +  2.0 * c         * f2 - c * f;
                vec2 w1 =  (2.0 - c) * f3 - (3.0 - c)        * f2         + 1.0;
                vec2 w2 = -(2.0 - c) * f3 + (3.0 -  2.0 * c) * f2 + c * f;
                vec2 w3 =         c  * f3 -                c * f2;

                vec2 w12 = w1 + w2;
                vec2 tc12 = (centerPosition + w2 / w12) / texSize;
                vec2 tc0 = (centerPosition - 1.0) / texSize;
                vec2 tc3 = (centerPosition + 2.0) / texSize;
                vec4 color = vec4(texture(u_History, vec2(tc12.x, tc0.y )).rgb, 1.0) * (w12.x * w0.y )
                           + vec4(texture(u_History, vec2(tc0.x,  tc12.y)).rgb, 1.0) * (w0.x  * w12.y)
                           + vec4(texture(u_History, vec2(tc12.x, tc12.y)).rgb, 1.0) * (w12.x * w12.y)
                           + vec4(texture(u_History, vec2(tc3.x,  tc12.y)).rgb, 1.0) * (w3.x  * w12.y)
                           + vec4(texture(u_History, vec2(tc12.x, tc3.y )).rgb, 1.0) * (w12.x * w3.y );
                if (abs(color.a) < 1.0e-6 || any(isnan(color)) || any(isinf(color))) {
                    return vec3(0.0);
                }
                vec3 result = color.rgb / color.a;
                return any(isnan(result)) || any(isinf(result))
                        ? vec3(0.0) : max(result, 0.0);
            }

            vec3 minOf(vec3 a, vec3 b, vec3 c, vec3 d, vec3 e) {
                return min(a, min(b, min(c, min(d, e))));
            }

            vec3 maxOf(vec3 a, vec3 b, vec3 c, vec3 d, vec3 e) {
                return max(a, max(b, max(c, max(d, e))));
            }

            vec3 reinhard(vec3 color) {
                if (any(isnan(color)) || any(isinf(color))) return vec3(0.0);
                color = max(color, 0.0);
                return color / (color + 1.0);
            }

            vec3 reinhardInverse(vec3 color) {
                color = clamp(color, vec3(0.0), vec3(1.0 - 1.0e-6));
                return color / max(1.0 - color, vec3(1.0e-6));
            }

            vec3 closestFragment(ivec2 center, ivec2 size) {
                ivec2 p0 = clamp(center, ivec2(0), size - 1);
                ivec2 p1 = clamp(center + ivec2(-2, -2), ivec2(0), size - 1);
                ivec2 p2 = clamp(center + ivec2( 2, -2), ivec2(0), size - 1);
                ivec2 p3 = clamp(center + ivec2(-2,  2), ivec2(0), size - 1);
                ivec2 p4 = clamp(center + ivec2( 2,  2), ivec2(0), size - 1);
                float d0 = texelFetch(u_Depth, p0, 0).r;
                float d1 = texelFetch(u_Depth, p1, 0).r;
                float d2 = texelFetch(u_Depth, p2, 0).r;
                float d3 = texelFetch(u_Depth, p3, 0).r;
                float d4 = texelFetch(u_Depth, p4, 0).r;
                // Photon selects the closest sample; SkyEngine uses reversed-Z.
                vec3 p = d0 > d1 ? vec3(p0, d0) : vec3(p1, d1);
                vec3 q = d2 > d3 ? vec3(p2, d2) : vec3(p3, d3);
                p = p.z > q.z ? p : q;
                return p.z > d4 ? p : vec3(p4, d4);
            }

            vec3 neighborhoodClipping(ivec2 texel, ivec2 size, vec3 current,
                    vec3 history, float distanceFactor) {
                ivec2 lo = ivec2(0);
                ivec2 hi = size - 1;
                vec3 a = texelFetch(u_Current, clamp(texel + ivec2(-1,  1), lo, hi), 0).rgb;
                vec3 b = texelFetch(u_Current, clamp(texel + ivec2( 0,  1), lo, hi), 0).rgb;
                vec3 c = texelFetch(u_Current, clamp(texel + ivec2( 1,  1), lo, hi), 0).rgb;
                vec3 d = texelFetch(u_Current, clamp(texel + ivec2(-1,  0), lo, hi), 0).rgb;
                vec3 e = current;
                vec3 f = texelFetch(u_Current, clamp(texel + ivec2( 1,  0), lo, hi), 0).rgb;
                vec3 g = texelFetch(u_Current, clamp(texel + ivec2(-1, -1), lo, hi), 0).rgb;
                vec3 h = texelFetch(u_Current, clamp(texel + ivec2( 0, -1), lo, hi), 0).rgb;
                vec3 i = texelFetch(u_Current, clamp(texel + ivec2( 1, -1), lo, hi), 0).rgb;

                a = rgbToYCoCg(reinhard(a));
                b = rgbToYCoCg(reinhard(b));
                c = rgbToYCoCg(reinhard(c));
                d = rgbToYCoCg(reinhard(d));
                e = rgbToYCoCg(reinhard(e));
                f = rgbToYCoCg(reinhard(f));
                g = rgbToYCoCg(reinhard(g));
                h = rgbToYCoCg(reinhard(h));
                i = rgbToYCoCg(reinhard(i));

                vec3 minColor = minOf(b, d, e, f, h);
                minColor = 0.5 * (minColor + minOf(minColor, a, c, g, i));
                vec3 maxColor = maxOf(b, d, e, f, h);
                maxColor = 0.5 * (maxColor + maxOf(maxColor, a, c, g, i));

                vec3 mean = (a + b + c + d + e + f + g + h + i) / 9.0;
                vec3 meanSquared = (a * a + b * b + c * c + d * d + e * e
                        + f * f + g * g + h * h + i * i) / 9.0;
                float gamma = mix(0.75, 1.25,
                        clamp((distanceFactor - 0.25) / 0.75, 0.0, 1.0));
                vec3 sigma = sqrt(max(meanSquared - mean * mean, vec3(0.0)));
                minColor = max(minColor, mean - gamma * sigma);
                maxColor = min(maxColor, mean + gamma * sigma);

                history = rgbToYCoCg(history);
                return yCoCgToRgb(clipAabb(history, minColor, maxColor));
            }

            void main() {
                /* Current ROH lesen — nie resampeln (s. Klassen-Kommentar) */
                ivec2 size = textureSize(u_Current, 0);
                ivec2 texel = ivec2(gl_FragCoord.xy);
                vec3 current = texelFetch(u_Current, texel, 0).rgb;
                if (any(isnan(current)) || any(isinf(current))) current = vec3(0.0);
                vec3 currentHdr = current;
                float handDepth = texelFetch(u_HandDepth, texel, 0).r;
                /* FirstPersonHandRenderer clears scene depth before drawing.  The captured
                   texture therefore contains only the hand/item and clear depth elsewhere. */
                bool hand = u_InverseDepth != 0
                        ? handDepth > 1.0e-7
                        : handDepth < 1.0 - 1.0e-7;
                /* The hand has no velocity buffer and is rendered after the captured
                   world depth. Reprojecting it as world geometry produces a large
                   rectangular history sample whenever water/sky is behind it. Photon
                   fixes hand depth separately; here the explicit hand-depth snapshot
                   gives the same hard history rejection. */
                if (hand) {
                    fragColor = vec4(current, 1.0);
                    return;
                }
                if (u_HistoryValid == 0) {
                    fragColor = vec4(current, 1.0);
                    return;
                }

                /* LEHRE (entfernter Sky-Early-Out, User-Befund "Laub jittert"): Early-Outs
                   im Resolve duerfen NIE von per-Frame-instabilen Bedingungen abhaengen.
                   Ein "d==0 -> rohes Current"-Kurzschluss klingt gratis (Himmel ist uniform),
                   aber Kronenpixel alternieren unter dem +-0,5-px-Jitter zwischen Blatt
                   (d!=0, akkumuliert) und Himmelsloch (d==0, roh) — die Akkumulation bricht
                   frameweise genau auf Alpha-Test-Kanten = sichtbares Laub-Jittern.
                   d==0 laeuft deshalb durch den normalen Pfad (Fernpunkt reprojiziert korrekt). */

                /* Kamerarelative Reprojektion in die Vorframe-UV */
                vec3 closest = closestFragment(texel, size);
                vec2 closestUv = (closest.xy + 0.5) / vec2(size);
                /* Infinite reversed-Z encodes clear sky as a homogeneous direction
                   (w==0), not a position.  It must be reprojected with w=0 and without
                   camera translation.  Dividing it by w (or replacing depth 0 by an
                   arbitrary finite distance) turns sky/water-edge pixels into huge
                   coordinates; invalid history UVs were the source of the flickering
                   black screen rectangle. */
                /* Photon c4_taa_exposure deliberately feeds the jittered screen sample
                   straight into the inverse *unjittered* projection.  At a static camera
                   this yields zero velocity, so the changing sub-pixel locations collect
                   in the same history pixel.  Removing the current jitter here instead
                   invents a per-frame velocity equal to the R2 offset and makes PCSS and
                   alpha-tested vegetation crawl whenever the history is sampled. */
                vec2 currentNdc = closestUv * 2.0 - 1.0;
                vec4 rel = u_InvProjView * vec4(currentNdc,
                        closest.z, 1.0);
                bool direction = abs(rel.w) < 1.0e-7;
                vec3 relPos = direction ? normalize(rel.xyz) : rel.xyz / rel.w;
                vec4 prevClip = direction
                        ? u_PrevProjView * vec4(rel.xyz, 0.0)
                        : u_PrevProjView * vec4(relPos + u_CamDelta, 1.0);
                if (prevClip.w <= 0.0 || any(isnan(prevClip)) || any(isinf(prevClip))) {
                    // hinter der Vorframe-Kamera oder nicht endlich: History sicher verwerfen
                    fragColor = vec4(current, 1.0);
                    return;
                }
                vec2 reprojectedUv = (prevClip.xy / prevClip.w) * 0.5 + 0.5;
                if (any(isnan(reprojectedUv)) || any(isinf(reprojectedUv))) {
                    fragColor = vec4(current, 1.0);
                    return;
                }
                vec2 velocity = closestUv - reprojectedUv;
                vec2 prevUv = v_uv - velocity;
                if (any(lessThan(prevUv, vec2(0.0))) || any(greaterThan(prevUv, vec2(1.0)))) {
                    fragColor = vec4(current, 1.0); // Disocclusion am Bildrand
                    return;
                }

                vec2 texSize = vec2(size);
                vec3 history = sampleHistoryCatmullRom(prevUv, texSize);

                /* 8-Nachbar-AABB in YCoCg + Richtungs-Clip (BSL NeighbourhoodClipping) */
                float pixelAge = texelFetch(u_History,
                        clamp(ivec2(prevUv * texSize), ivec2(0), size - 1), 0).a + 1.0;
                if (isnan(pixelAge) || isinf(pixelAge)) pixelAge = 1.0;
                pixelAge = clamp(pixelAge, 1.0, 64.0);
                float distanceFactor = 1.0 - exp2(-0.025 * length(relPos));
                float alpha = max(1.0 / pixelAge, mix(0.35, 0.10, distanceFactor));

                current = reinhard(current);
                history = reinhard(history);
                history = neighborhoodClipping(texel, size, current, history, distanceFactor);

                /* BSL-Blend: Bewegung mischt sofort ~30 % frisches Current dazu (scharf),
                   Standbild akkumuliert mit u_HistoryWeight (Default 0.9). */
                vec2 pixelOffset = 1.0 - abs(2.0 * fract(texSize * prevUv) - 1.0);
                float offcenter = sqrt(pixelOffset.x * pixelOffset.y) * 0.25 + 0.75;
                alpha = 1.0 - (1.0 - alpha) * offcenter;
                vec3 result = reinhardInverse(mix(history, current, alpha));
                if (any(isnan(result)) || any(isinf(result))) result = currentHdr;
                fragColor = vec4(result, pixelAge * offcenter);
            }
            """;

    /* History -> GuiScreen; Schaerfung (settings.sharpen 0..1) NUR auf der Ausgabe.
       Operator: AMD CAS (Contrast Adaptive Sharpening) — per-Pixel-adaptive Staerke,
       halo-frei by design. FALLE (dokumentiert, nicht wiederholen): die fruehere
       Unsharp+Nachbar-Clamp-Fassung war auf Voxel-Texturen wirkungslos, weil fast
       jeder Texel ein lokales Extremum ist und der Clamp den Effekt auffrass. */
    private static final String COPY_FRAGMENT_SHADER = """
            #version 460 core

            in vec2 v_uv;
            out vec4 fragColor;

            uniform sampler2D u_Input;
            uniform float u_Sharpen; // UI 0 = aus; Photon-Default 0.5 -> CAS sharpness 0
            uniform int u_DisplayTransform;

            vec3 displayEotf(vec3 linear) {
                return 1.14374 * (-0.126893 * linear + sqrt(max(linear, 0.0)));
            }

            vec3 minOf(vec3 a, vec3 b, vec3 c, vec3 d, vec3 e) {
                return min(a, min(b, min(c, min(d, e))));
            }

            vec3 maxOf(vec3 a, vec3 b, vec3 c, vec3 d, vec3 e) {
                return max(a, max(b, max(c, max(d, e))));
            }

            void main() {
                ivec2 texel = ivec2(gl_FragCoord.xy);
                vec3 e = texelFetch(u_Input, texel, 0).rgb;
                if (u_DisplayTransform != 0) e = displayEotf(e);
                if (u_DisplayTransform != 0 && u_Sharpen > 0.0) {
                    // Photon program/final.fsh, unveraenderter FidelityFX-CAS-Kernel.
                    vec3 a = displayEotf(texelFetch(u_Input, texel + ivec2(-1, -1), 0).rgb);
                    vec3 b = displayEotf(texelFetch(u_Input, texel + ivec2( 0, -1), 0).rgb);
                    vec3 c = displayEotf(texelFetch(u_Input, texel + ivec2( 1, -1), 0).rgb);
                    vec3 d = displayEotf(texelFetch(u_Input, texel + ivec2(-1,  0), 0).rgb);
                    vec3 f = displayEotf(texelFetch(u_Input, texel + ivec2( 1,  0), 0).rgb);
                    vec3 g = displayEotf(texelFetch(u_Input, texel + ivec2(-1,  1), 0).rgb);
                    vec3 h = displayEotf(texelFetch(u_Input, texel + ivec2( 0,  1), 0).rgb);
                    vec3 i = displayEotf(texelFetch(u_Input, texel + ivec2( 1,  1), 0).rgb);

                    vec3 minColor = minOf(d, e, f, b, h);
                    minColor += minOf(minColor, a, c, g, i);
                    vec3 maxColor = maxOf(d, e, f, b, h);
                    maxColor += maxOf(maxColor, a, c, g, i);

                    vec3 w = clamp(min(minColor, 2.0 - maxColor)
                            / max(maxColor, vec3(1e-6)), 0.0, 1.0);
                    w = 1.0 - (1.0 - w) * (1.0 - w);
                    float photonSharpness = u_Sharpen * 2.0 - 1.0;
                    w *= -1.0 / mix(8.0, 5.0, photonSharpness);
                    e = clamp((b + d + f + h) * w + e, 0.0, 1.0)
                            / (1.0 + 4.0 * w);
                }
                fragColor = vec4(e, 1.0);
            }
            """;

    private ShaderProgram fxaaProgram;
    private ShaderProgram taaProgram;
    private ShaderProgram copyProgram;
    private final Stage stage;

    /* TAA-History: Ping-Pong (RGBA16F gegen Akkumulations-Quantisierung), Besitz hier. */
    private final int[] historyTex = new int[2];
    private final int[] historyFbo = new int[2];
    private int workTex;
    private int workFbo;
    private int historyWrite;
    private boolean historyValid;
    /* Frame-Zaehler des letzten TAA-Resolves: Luecke (NONE/FXAA dazwischen, Resize)
       => History ist veraltet und wird verworfen. */
    private long lastTaaFrame = Long.MIN_VALUE;
    private boolean warnedNoDepth;

    private final Logger logger = LogManager.getLogger(AntiAliasingPass.class.getName());

    public AntiAliasingPass(Stage stage) {
        this.stage = stage;
    }

    /** Discards temporal data after camera-medium changes or other discontinuities. */
    public void invalidateHistory() {
        if (this.stage == Stage.TEMPORAL) this.historyValid = false;
    }

    @Override
    public void init(PostContext context) {
        this.fxaaProgram = new ShaderProgram(
                new Shader(PostProcessor.FULLSCREEN_VERTEX_SHADER, ShaderType.VERTEX),
                new Shader(FXAA_FRAGMENT_SHADER, ShaderType.FRAGMENT));
        this.fxaaProgram.bind();
        this.fxaaProgram.setUniformi("u_Input", 0);
        this.fxaaProgram.unbind();

        this.taaProgram = new ShaderProgram(
                new Shader(PostProcessor.FULLSCREEN_VERTEX_SHADER, ShaderType.VERTEX),
                new Shader(TAA_FRAGMENT_SHADER, ShaderType.FRAGMENT));
        this.taaProgram.bind();
        this.taaProgram.setUniformi("u_Current", 0);
        this.taaProgram.setUniformi("u_History", 1);
        this.taaProgram.setUniformi("u_Depth", 2);
        this.taaProgram.setUniformi("u_HandDepth", 3);
        this.taaProgram.unbind();

        this.copyProgram = new ShaderProgram(
                new Shader(PostProcessor.FULLSCREEN_VERTEX_SHADER, ShaderType.VERTEX),
                new Shader(COPY_FRAGMENT_SHADER, ShaderType.FRAGMENT));
        this.copyProgram.bind();
        this.copyProgram.setUniformi("u_Input", 0);
        this.copyProgram.setUniformi("u_DisplayTransform", 0);
        this.copyProgram.unbind();

        if (this.stage == Stage.TEMPORAL) {
            this.createHistoryTargets(context.width, context.height);
        } else {
            this.createWorkTarget(context.width, context.height);
        }
    }

    @Override
    public void resize(PostContext context) {
        this.disposeTargets();
        if (this.stage == Stage.TEMPORAL) {
            this.createHistoryTargets(context.width, context.height);
        } else {
            this.createWorkTarget(context.width, context.height);
        }
        this.historyValid = false;
    }

    @Override
    public boolean isActive(PostContext context) {
        AntiAliasingMode mode = context.settings.getAaMode();
        if (this.stage == Stage.TEMPORAL) return context.settings.isTemporalAa();
        /* Photons final pass ist zugleich der lineare-Rec.709 -> sRGB Output-Transform und
           darf daher selbst ohne FXAA/CAS nie uebersprungen werden. */
        return true;
    }

    @Override
    public void execute(PostContext context) {
        if (this.stage == Stage.FINAL) {
            this.executeFinal(context);
            return;
        }
        if (context.sceneDepth == 0) {
            if (!this.warnedNoDepth) {
                this.logger.warning("TAA ohne Depth-Textur (Framebuffer noch MSAA?) — Copy-Fallback");
                this.warnedNoDepth = true;
            }
            this.historyValid = false;
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, context.targetFbo);
            this.copyProgram.bind();
            this.copyProgram.setUniformf("u_Sharpen", 0F);
            this.copyProgram.setUniformi("u_DisplayTransform", 0);
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, context.input);
            context.drawFullscreenTriangle();
            this.copyProgram.unbind();
            return;
        }

        /* Aussetzer erkennen (Modus-Wechsel, Pass uebersprungen): History nur gueltig,
           wenn der letzte Resolve im direkt vorherigen Frame lief. */
        if (context.frame != this.lastTaaFrame + 1) this.historyValid = false;
        /* A teleport has no valid screen-space correspondence. Letting the old HDR history
           survive it can turn a rejected/NaN reprojection into a large black rectangle. */
        float dx = context.camDelta.x;
        float dy = context.camDelta.y;
        float dz = context.camDelta.z;
        if (!Float.isFinite(dx) || !Float.isFinite(dy) || !Float.isFinite(dz)
                || dx * dx + dy * dy + dz * dz > 64F) {
            this.historyValid = false;
        }
        this.lastTaaFrame = context.frame;

        int write = this.historyWrite;
        int read = 1 - write;

        /* Photon-Reihenfolge: TAA auf der linearen HDR-Szene vor Bloom und Grading. */
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, this.historyFbo[write]);
        this.taaProgram.bind();
        this.taaProgram.setUniformMatrix4f("u_InvProjView", context.invProjView);
        this.taaProgram.setUniformMatrix4f("u_PrevProjView", context.prevProjView);
        this.taaProgram.setUniformVector3f("u_CamDelta", context.camDelta);
        this.taaProgram.setUniformi("u_HistoryValid", this.historyValid ? 1 : 0);
        this.taaProgram.setUniformi("u_InverseDepth",
                SkyEngine.get().getWindow().getProperties().isUseInverseDepth() ? 1 : 0);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, context.input);
        GL13.glActiveTexture(GL13.GL_TEXTURE1);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, this.historyTex[read]);
        GL13.glActiveTexture(GL13.GL_TEXTURE2);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, context.sceneDepth);
        GL13.glActiveTexture(GL13.GL_TEXTURE3);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, context.handDepth);
        context.drawFullscreenTriangle();
        this.taaProgram.unbind();

        /* History in die Post-Kette kopieren; CAS läuft erst nach Grading/FXAA. */
        context.history = this.historyTex[write];
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, context.targetFbo);
        this.copyProgram.bind();
        this.copyProgram.setUniformf("u_Sharpen", 0F);
        /* TAA history is still scene-linear Rec.2020 HDR. Applying final.fsh's display
           EOTF here made Bloom and Lottes consume gamma-encoded values as linear light,
           producing the washed-out, over-bright image. This copy must be bitwise-linear. */
        this.copyProgram.setUniformi("u_DisplayTransform", 0);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, this.historyTex[write]);
        context.drawFullscreenTriangle();
        this.copyProgram.unbind();
        GL13.glActiveTexture(GL13.GL_TEXTURE0);

        this.historyValid = true;
        this.historyWrite = read;
    }

    private void executeFinal(PostContext context) {
        AntiAliasingMode mode = context.settings.getAaMode();
        boolean fxaa = mode == AntiAliasingMode.FXAA || mode == AntiAliasingMode.TAA_FXAA;
        float sharpen = context.settings.getSharpen();
        int current = context.input;

        if (fxaa) {
            /* FXAA runs on linear display-referred Rec.709, but Photon final.fsh still has
               to apply the display EOTF afterwards. Always keep FXAA off the default FBO. */
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, this.workFbo);
            this.fxaaProgram.bind();
            this.fxaaProgram.setUniformVector2f("u_InvResolution",
                    1F / context.width, 1F / context.height);
            this.fxaaProgram.setUniformf("u_SubpixelScale", 1F);
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, context.input);
            context.drawFullscreenTriangle();
            this.fxaaProgram.unbind();
            current = this.workTex;
        }

        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, context.targetFbo);
        this.copyProgram.bind();
        this.copyProgram.setUniformf("u_Sharpen", sharpen);
        this.copyProgram.setUniformi("u_DisplayTransform", 1);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, current);
        context.drawFullscreenTriangle();
        this.copyProgram.unbind();
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
    }

    private void createHistoryTargets(int width, int height) {
        for (int i = 0; i < 2; i++) {
            this.historyTex[i] = GL11.glGenTextures();
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, this.historyTex[i]);
            /* TAA läuft vor dem Tonemapping und braucht daher echten HDR-Headroom. */
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_RGBA16F, width, height, 0,
                    GL11.GL_RGBA, GL11.GL_FLOAT, (ByteBuffer) null);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);

            this.historyFbo[i] = GL30.glGenFramebuffers();
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, this.historyFbo[i]);
            GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0,
                    GL11.GL_TEXTURE_2D, this.historyTex[i], 0);
        }
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
    }

    private void disposeHistoryTargets() {
        for (int i = 0; i < 2; i++) {
            if (this.historyFbo[i] != 0) GL30.glDeleteFramebuffers(this.historyFbo[i]);
            if (this.historyTex[i] != 0) GL11.glDeleteTextures(this.historyTex[i]);
            this.historyFbo[i] = 0;
            this.historyTex[i] = 0;
        }
    }

    private void createWorkTarget(int width, int height) {
        this.workTex = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, this.workTex);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_RGBA16F, width, height, 0,
                GL11.GL_RGBA, GL11.GL_FLOAT, (ByteBuffer) null);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        this.workFbo = GL30.glGenFramebuffers();
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, this.workFbo);
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0,
                GL11.GL_TEXTURE_2D, this.workTex, 0);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
    }

    private void disposeTargets() {
        this.disposeHistoryTargets();
        if (this.workFbo != 0) GL30.glDeleteFramebuffers(this.workFbo);
        if (this.workTex != 0) GL11.glDeleteTextures(this.workTex);
        this.workFbo = 0;
        this.workTex = 0;
    }

    @Override
    public void dispose() {
        if (this.fxaaProgram != null) this.fxaaProgram.dispose();
        if (this.taaProgram != null) this.taaProgram.dispose();
        if (this.copyProgram != null) this.copyProgram.dispose();
        this.disposeTargets();
    }
}
