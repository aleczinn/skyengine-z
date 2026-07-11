package de.skyengine.graphics.post.passes;

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
 * <b>NONE</b> = Pass inaktiv (Grading schreibt direkt in den Screen),
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
 * Copy-/Sharpen-Schritt bringt sie auf den Screen — Sharpen ({@code settings.sharpen})
 * wirkt NUR auf die Ausgabe, nie in die History (Feedback-Artefakte).
 *
 * <p><b>Voraussetzung:</b> {@code sceneDepth} existiert nur bei msaaSamples=0 — bei
 * TAA + MSAA fällt der Pass mit Log-Hinweis auf FXAA zurück. SMAA wäre ein weiterer
 * Modus im selben Switch.
 *
 * <p>Läuft display-referred NACH dem Grading (FXAA erwartet LDR-Luma).
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
            /* 1.0 = pures FXAA; 0.5 als TAA-Vorstufe (BSL halbiert den Subpixel-Anteil
               unter TAA — die zeitliche Akkumulation uebernimmt das Subpixel-Glaetten). */
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
            uniform mat4 u_InvProjView;     // Inverse der GEJITTERTEN PV des Frames
            uniform mat4 u_PrevProjView;    // UNGEJITTERTE PV des Vorframes
            uniform vec3 u_CamDelta;        // camNow - camPrev: P_relPrev = P_relNow + delta
            uniform int u_HistoryValid;     // 0 = erster Frame nach Reset -> nur aktuell
            /* Standbild-History-Gewicht (settings.taaHistoryWeight, Default 0.9 = BSL);
               Bewegung senkt es ueber exp(-velocity) bis auf (weight - 0.2) ab. */
            uniform float u_HistoryWeight;

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
                vec3 eClip = 0.5 * (aabbMax - aabbMin) + 0.00000001;
                vec3 vClip = q - pClip;
                vec3 vUnit = vClip / eClip;
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

                const float c = 0.7;
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
                return max(color.rgb / color.a, 0.0);
            }

            void main() {
                /* Current ROH lesen — nie resampeln (s. Klassen-Kommentar) */
                vec3 current = texture(u_Current, v_uv).rgb;
                if (u_HistoryValid == 0) {
                    fragColor = vec4(current, 1.0);
                    return;
                }

                /* Kamerarelative Reprojektion in die Vorframe-UV */
                float d = texture(u_Depth, v_uv).r;
                vec4 rel = u_InvProjView * vec4(v_uv * 2.0 - 1.0, d, 1.0);
                vec3 relPos = rel.xyz / rel.w;
                vec4 prevClip = u_PrevProjView * vec4(relPos + u_CamDelta, 1.0);
                if (prevClip.w <= 0.0) { // hinter der Vorframe-Kamera
                    fragColor = vec4(current, 1.0);
                    return;
                }
                vec2 prevUv = (prevClip.xy / prevClip.w) * 0.5 + 0.5;
                if (any(lessThan(prevUv, vec2(0.0))) || any(greaterThan(prevUv, vec2(1.0)))) {
                    fragColor = vec4(current, 1.0); // Disocclusion am Bildrand
                    return;
                }

                vec2 texSize = vec2(textureSize(u_History, 0));
                vec3 history = sampleHistoryCatmullRom(prevUv, texSize);

                /* 8-Nachbar-AABB in YCoCg + Richtungs-Clip (BSL NeighbourhoodClipping) */
                vec2 texel = 1.0 / texSize;
                vec3 minClr = rgbToYCoCg(current);
                vec3 maxClr = minClr;
                for (int y = -1; y <= 1; y++) {
                    for (int x = -1; x <= 1; x++) {
                        if (x == 0 && y == 0) continue;
                        vec3 clr = rgbToYCoCg(texture(u_Current, v_uv + vec2(x, y) * texel).rgb);
                        minClr = min(minClr, clr);
                        maxClr = max(maxClr, clr);
                    }
                }
                history = yCoCgToRgb(clipAabb(rgbToYCoCg(history), minClr, maxClr));

                /* BSL-Blend: Bewegung mischt sofort ~30 % frisches Current dazu (scharf),
                   Standbild akkumuliert mit u_HistoryWeight (Default 0.9). */
                float velPx = length((v_uv - prevUv) * texSize);
                float weight = exp(-velPx) * 0.2 + (u_HistoryWeight - 0.2);

                fragColor = vec4(mix(current, history, clamp(weight, 0.0, 0.98)), 1.0);
            }
            """;

    /* History -> Screen; Schaerfung (settings.sharpen 0..1) NUR auf der Ausgabe.
       Operator: AMD CAS (Contrast Adaptive Sharpening) — per-Pixel-adaptive Staerke,
       halo-frei by design. FALLE (dokumentiert, nicht wiederholen): die fruehere
       Unsharp+Nachbar-Clamp-Fassung war auf Voxel-Texturen wirkungslos, weil fast
       jeder Texel ein lokales Extremum ist und der Clamp den Effekt auffrass. */
    private static final String COPY_FRAGMENT_SHADER = """
            #version 460 core

            in vec2 v_uv;
            out vec4 fragColor;

            uniform sampler2D u_Input;
            uniform float u_Sharpen; // 0 = reiner Copy, sinnvoll 0..1

            void main() {
                vec3 e = texture(u_Input, v_uv).rgb;
                if (u_Sharpen > 0.0) {
                    vec3 a = textureOffset(u_Input, v_uv, ivec2(-1, 0)).rgb;
                    vec3 b = textureOffset(u_Input, v_uv, ivec2( 1, 0)).rgb;
                    vec3 c = textureOffset(u_Input, v_uv, ivec2( 0,-1)).rgb;
                    vec3 d = textureOffset(u_Input, v_uv, ivec2( 0, 1)).rgb;
                    vec3 minRGB = min(min(a, b), min(min(c, d), e));
                    vec3 maxRGB = max(max(a, b), max(max(c, d), e));
                    /* CAS-Gewicht: viel Schaerfung wo Kontrast-Spielraum ist, wenig an
                       bereits harten Kanten (deshalb kein Ringing). */
                    vec3 amp = sqrt(clamp(min(minRGB, 2.0 - maxRGB) / max(maxRGB, vec3(1e-5)), 0.0, 1.0));
                    float peak = -1.0 / mix(8.0, 5.0, clamp(u_Sharpen, 0.0, 1.0));
                    vec3 w = amp * peak;
                    e = clamp((e + (a + b + c + d) * w) / (4.0 * w + 1.0), 0.0, 1.0);
                }
                fragColor = vec4(e, 1.0);
            }
            """;

    private ShaderProgram fxaaProgram;
    private ShaderProgram taaProgram;
    private ShaderProgram copyProgram;

    /* TAA-History: Ping-Pong (RGBA16F gegen Akkumulations-Quantisierung), Besitz hier. */
    private final int[] historyTex = new int[2];
    private final int[] historyFbo = new int[2];
    private int historyWrite;
    private boolean historyValid;
    /* Frame-Zaehler des letzten TAA-Resolves: Luecke (NONE/FXAA dazwischen, Resize)
       => History ist veraltet und wird verworfen. */
    private long lastTaaFrame = Long.MIN_VALUE;
    private boolean warnedNoDepth;

    private final Logger logger = LogManager.getLogger(AntiAliasingPass.class.getName());

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
        this.taaProgram.unbind();

        this.copyProgram = new ShaderProgram(
                new Shader(PostProcessor.FULLSCREEN_VERTEX_SHADER, ShaderType.VERTEX),
                new Shader(COPY_FRAGMENT_SHADER, ShaderType.FRAGMENT));
        this.copyProgram.bind();
        this.copyProgram.setUniformi("u_Input", 0);
        this.copyProgram.unbind();

        this.createHistoryTargets(context.width, context.height);
    }

    @Override
    public void resize(PostContext context) {
        this.disposeHistoryTargets();
        this.createHistoryTargets(context.width, context.height);
        this.historyValid = false;
    }

    @Override
    public boolean isActive(PostContext context) {
        return context.settings.getAaMode() != AntiAliasingMode.NONE;
    }

    @Override
    public void execute(PostContext context) {
        boolean taa = context.settings.getAaMode() == AntiAliasingMode.TAA;
        if (taa && context.sceneDepth == 0) {
            /* Ohne Depth-Textur (MSAA > 0) keine Reprojektion moeglich -> FXAA-Fallback. */
            if (!this.warnedNoDepth) {
                this.logger.warning("TAA braucht msaaSamples=0 (Depth-Textur) — falle auf FXAA zurück");
                this.warnedNoDepth = true;
            }
            taa = false;
        }

        if (!taa) {
            this.historyValid = false;
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, context.targetFbo);
            this.fxaaProgram.bind();
            this.fxaaProgram.setUniformVector2f("u_InvResolution", 1F / context.width, 1F / context.height);
            this.fxaaProgram.setUniformf("u_SubpixelScale", 1.0F);
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, context.input);
            context.drawFullscreenTriangle();
            this.fxaaProgram.unbind();
            return;
        }

        /* Aussetzer erkennen (Modus-Wechsel, Pass uebersprungen): History nur gueltig,
           wenn der letzte Resolve im direkt vorherigen Frame lief. */
        if (context.frame != this.lastTaaFrame + 1) this.historyValid = false;
        this.lastTaaFrame = context.frame;

        int write = this.historyWrite;
        int read = 1 - write;

        /* 1) FXAA-Vorstufe (BSL: FXAA + TAA laufen zusammen): glaettet die Kanten des
           aktuellen Frames VOR der Akkumulation (weniger Flimmer-Input), Subpixel-Anteil
           halbiert — exakt BSLs "#ifdef TAA". Ziel: Ping-0-Zwischentextur. */
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, context.pingFbo(0));
        this.fxaaProgram.bind();
        this.fxaaProgram.setUniformVector2f("u_InvResolution", 1F / context.width, 1F / context.height);
        this.fxaaProgram.setUniformf("u_SubpixelScale", 0.5F);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, context.input);
        context.drawFullscreenTriangle();
        this.fxaaProgram.unbind();

        /* 2) Resolve -> History-Write (nie direkt der Screen — das Ergebnis muss persistieren) */
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, this.historyFbo[write]);
        this.taaProgram.bind();
        this.taaProgram.setUniformMatrix4f("u_InvProjView", context.invProjView);
        this.taaProgram.setUniformMatrix4f("u_PrevProjView", context.prevProjView);
        this.taaProgram.setUniformVector3f("u_CamDelta", context.camDelta);
        this.taaProgram.setUniformi("u_HistoryValid", this.historyValid ? 1 : 0);
        this.taaProgram.setUniformf("u_HistoryWeight", context.settings.getTaaHistoryWeight());
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, context.pingTexture(0)); // FXAA-geglaettetes Current
        GL13.glActiveTexture(GL13.GL_TEXTURE1);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, this.historyTex[read]);
        GL13.glActiveTexture(GL13.GL_TEXTURE2);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, context.sceneDepth);
        context.drawFullscreenTriangle();
        this.taaProgram.unbind();

        /* 3) Copy/Sharpen (CAS) -> Ziel; History-Slot fuer spaetere Paesse publizieren */
        context.history = this.historyTex[write];
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, context.targetFbo);
        this.copyProgram.bind();
        this.copyProgram.setUniformf("u_Sharpen", context.settings.getSharpen());
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, this.historyTex[write]);
        context.drawFullscreenTriangle();
        this.copyProgram.unbind();
        GL13.glActiveTexture(GL13.GL_TEXTURE0);

        this.historyValid = true;
        this.historyWrite = read;
    }

    private void createHistoryTargets(int width, int height) {
        for (int i = 0; i < 2; i++) {
            this.historyTex[i] = GL11.glGenTextures();
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, this.historyTex[i]);
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_RGBA16F, width, height, 0,
                    GL11.GL_RGBA, GL30.GL_HALF_FLOAT, (ByteBuffer) null);
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

    @Override
    public void dispose() {
        if (this.fxaaProgram != null) this.fxaaProgram.dispose();
        if (this.taaProgram != null) this.taaProgram.dispose();
        if (this.copyProgram != null) this.copyProgram.dispose();
        this.disposeHistoryTargets();
    }
}
