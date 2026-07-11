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

    /* TAA-Resolve: Reprojektion + Farb-AABB-Clamp + Blend, schreibt in die History.
       Reversed-Z + ClipControl ZERO_TO_ONE: Depth-Sample d IST Clip-z in [0,1] (kein
       z*2-1!), d=0 = fern (Clear). Matrizen/Delta s. Camera-Javadoc. */
    private static final String TAA_FRAGMENT_SHADER = """
            #version 460 core

            in vec2 v_uv;
            out vec4 fragColor;

            uniform sampler2D u_Current;    // LDR nach Grading (gejittert gerendert)
            uniform sampler2D u_History;    // Read-Seite des Ping-Pongs (Vorframe-Resolve)
            uniform sampler2D u_Depth;      // Szene-Depth (32F, Reversed-Z)
            uniform mat4 u_InvProjView;     // Inverse der GEJITTERTEN PV des Frames
            uniform mat4 u_PrevProjView;    // UNGEJITTERTE PV des Vorframes
            uniform vec3 u_CamDelta;        // camNow - camPrev: P_relPrev = P_relNow + delta
            uniform int u_HistoryValid;     // 0 = erster Frame nach Reset -> nur aktuell
            /* History-Gewicht (settings.taaHistoryWeight): hoeher = ruhiger/traeger,
               niedriger = schaerfer/flimmriger. Bei Bewegung adaptiv abgesenkt (s. main). */
            uniform float u_HistoryWeight;

            /*
             * Catmull-Rom-Sampling (bikubisch, 9 bilineare Taps, Jimenez-Schema) statt
             * bilinear: prevUv liegt bei stehender Kamera JEDEN Frame um den aktuellen
             * Jitter (+-0,5 px) versetzt — bilineares Resampling faltet die History damit
             * pro Frame erneut mit dem Bilinear-Zelt und akkumuliert eine IIR-Weich-
             * zeichnung weit ueber 1 px (Befund: Ferne "extrem blurry"). Catmull-Rom
             * rekonstruiert nahezu verlustfrei. NIE auf bilinear zurueckbauen!
             */
            vec3 sampleHistoryCatmullRom(vec2 uv, vec2 texSize) {
                vec2 samplePos = uv * texSize;
                vec2 texPos1 = floor(samplePos - 0.5) + 0.5;
                vec2 f = samplePos - texPos1;
                vec2 w0 = f * (-0.5 + f * (1.0 - 0.5 * f));
                vec2 w1 = 1.0 + f * f * (-2.5 + 1.5 * f);
                vec2 w2 = f * (0.5 + f * (2.0 - 1.5 * f));
                vec2 w3 = f * f * (-0.5 + 0.5 * f);
                vec2 w12 = w1 + w2;
                vec2 offset12 = w2 / w12;
                vec2 texPos0 = (texPos1 - 1.0) / texSize;
                vec2 texPos3 = (texPos1 + 2.0) / texSize;
                vec2 texPos12 = (texPos1 + offset12) / texSize;
                vec3 result =
                      texture(u_History, vec2(texPos0.x,  texPos0.y)).rgb  * (w0.x  * w0.y)
                    + texture(u_History, vec2(texPos12.x, texPos0.y)).rgb  * (w12.x * w0.y)
                    + texture(u_History, vec2(texPos3.x,  texPos0.y)).rgb  * (w3.x  * w0.y)
                    + texture(u_History, vec2(texPos0.x,  texPos12.y)).rgb * (w0.x  * w12.y)
                    + texture(u_History, vec2(texPos12.x, texPos12.y)).rgb * (w12.x * w12.y)
                    + texture(u_History, vec2(texPos3.x,  texPos12.y)).rgb * (w3.x  * w12.y)
                    + texture(u_History, vec2(texPos0.x,  texPos3.y)).rgb  * (w0.x  * w3.y)
                    + texture(u_History, vec2(texPos12.x, texPos3.y)).rgb  * (w12.x * w3.y)
                    + texture(u_History, vec2(texPos3.x,  texPos3.y)).rgb  * (w3.x  * w3.y);
                return max(result, 0.0); // CR kann unterschwingen
            }

            void main() {
                vec3 current = texture(u_Current, v_uv).rgb;
                if (u_HistoryValid == 0) {
                    fragColor = vec4(current, 1.0);
                    return;
                }

                /* 3x3-Farb-AABB des aktuellen Frames: Clamp-Huelle gegen Ghosting
                   (bewegte Items/Wasseranimation haben keinen Velocity-Buffer). */
                vec3 minC = current, maxC = current;
                for (int y = -1; y <= 1; y++) {
                    for (int x = -1; x <= 1; x++) {
                        vec3 c = textureOffset(u_Current, v_uv, ivec2(x, y)).rgb;
                        minC = min(minC, c);
                        maxC = max(maxC, c);
                    }
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
                vec3 history = clamp(sampleHistoryCatmullRom(prevUv, texSize), minC, maxC);

                /* Bewegungsadaptiv: bei schneller Kamerabewegung weniger History
                   (schaerfer, minimal flimmriger) — Standbild bleibt voll stabil. */
                float velPx = length((prevUv - v_uv) * texSize);
                float weight = u_HistoryWeight * (1.0 - 0.35 * clamp(velPx / 8.0, 0.0, 1.0));

                fragColor = vec4(mix(current, history, weight), 1.0);
            }
            """;

    /* History -> Screen; optionaler Unsharp-Term (settings.sharpen) NUR auf der Ausgabe. */
    private static final String COPY_FRAGMENT_SHADER = """
            #version 460 core

            in vec2 v_uv;
            out vec4 fragColor;

            uniform sampler2D u_Input;
            uniform float u_Sharpen; // 0 = reiner Copy

            void main() {
                vec3 c = texture(u_Input, v_uv).rgb;
                if (u_Sharpen > 0.0) {
                    vec3 n0 = textureOffset(u_Input, v_uv, ivec2( 1, 0)).rgb;
                    vec3 n1 = textureOffset(u_Input, v_uv, ivec2(-1, 0)).rgb;
                    vec3 n2 = textureOffset(u_Input, v_uv, ivec2( 0, 1)).rgb;
                    vec3 n3 = textureOffset(u_Input, v_uv, ivec2( 0,-1)).rgb;
                    vec3 blur = (n0 + n1 + n2 + n3) * 0.25;
                    /* Faktor 2: sharpen 0.5 (BSL-uebliche Staerke) ist deutlich sichtbar.
                       Clamp gegen die lokale Nachbarschaft statt hart [0,1] — kein
                       Ringing/Halo an harten Kanten. */
                    vec3 sharpened = c + (c - blur) * (u_Sharpen * 2.0);
                    vec3 minN = min(c, min(min(n0, n1), min(n2, n3)));
                    vec3 maxN = max(c, max(max(n0, n1), max(n2, n3)));
                    c = clamp(sharpened, minN, maxN);
                }
                fragColor = vec4(clamp(c, 0.0, 1.0), 1.0);
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

        /* 1) Resolve -> History-Write (nie direkt der Screen — das Ergebnis muss persistieren) */
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, this.historyFbo[write]);
        this.taaProgram.bind();
        this.taaProgram.setUniformMatrix4f("u_InvProjView", context.invProjView);
        this.taaProgram.setUniformMatrix4f("u_PrevProjView", context.prevProjView);
        this.taaProgram.setUniformVector3f("u_CamDelta", context.camDelta);
        this.taaProgram.setUniformi("u_HistoryValid", this.historyValid ? 1 : 0);
        this.taaProgram.setUniformf("u_HistoryWeight", context.settings.getTaaHistoryWeight());
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, context.input);
        GL13.glActiveTexture(GL13.GL_TEXTURE1);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, this.historyTex[read]);
        GL13.glActiveTexture(GL13.GL_TEXTURE2);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, context.sceneDepth);
        context.drawFullscreenTriangle();
        this.taaProgram.unbind();

        /* 2) Copy/Sharpen -> Ziel; History-Slot fuer spaetere Paesse publizieren */
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
