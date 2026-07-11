package de.skyengine.graphics.post.passes;

import de.skyengine.graphics.post.PostContext;
import de.skyengine.graphics.post.PostPass;
import de.skyengine.graphics.post.PostProcessor;
import de.skyengine.graphics.shader.Shader;
import de.skyengine.graphics.shader.ShaderProgram;
import de.skyengine.graphics.shader.ShaderType;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL30;

/**
 * Color-Grading in EINEM internen Shader (effizient), als eigener Pass der Kette (erweiterbar).
 * Reihenfolge nach Domänen — muss zum UBO-Layout in {@link PostProcessor} passen:
 * <pre>
 * szenen-linear (HDR):   Exposure → White Balance (Temperature/Tint)
 * Domänenwechsel:        Tonemap-Operator (NONE/REINHARD/ACES)
 * display-referred [0,1]: Lift/Gain → Shadows/Midtones/Highlights → Contrast → Brightness
 *                         → Saturation → Vibrance (gewichtet schwach gesättigte Farben)
 * Output Transform:      Gamma zuletzt
 * </pre>
 * Grading NACH dem Tonemap ist Absicht: display-referred verhalten sich die Regler wie in
 * Lightroom/Resolve; HDR-Grading bräuchte Log-Räume (Overkill). Der spätere 3D-LUT-Pass
 * hängt display-referred hinter diesem Pass (vor AA). Mit Neutral-Defaults ist der Pass
 * ein Copy (bis auf &lt;1-ULP-Rundung im Contrast-Term — bei 8 Bit unsichtbar).
 *
 * <p>Randnotiz: Ein echter Linear-Workflow (sRGB-Textur-Decode, lineares Licht) existiert in
 * der Engine noch nicht — er kommt mit dem Licht-Merge; die Kettenreihenfolge ist darauf
 * bereits ausgelegt.
 */
public final class ColorGradingPass implements PostPass {

    private static final String FRAGMENT_SHADER = """
            #version 460 core

            in vec2 v_uv;
            out vec4 fragColor;

            uniform sampler2D u_Scene;

            /* Layout muss PostProcessor.uploadUbo spiegeln (4x vec4, gepackte Skalare). */
            layout(std140, binding = 2) uniform u_PostSettings {
                vec4 egcs; // exposure, gamma, contrast, saturation
                vec4 vbtt; // vibrance, brightness, temperature, tint
                vec4 lgsh; // lift, gain, shadows, highlights
                vec4 mtdr; // midtones, tonemapOperator, debugMode, reserviert
            };

            const vec3 LUMA = vec3(0.2126, 0.7152, 0.0722);

            void main() {
                vec3 c = texture(u_Scene, v_uv).rgb;

                /* --- szenen-linear (HDR) --- */
                c *= egcs.x; // Exposure

                /* White Balance — bewusst einfache, dokumentierte Approximation:
                   temperature verschiebt Rot/Blau, tint Gruen/Magenta (neutral bei 0).
                   Praezise Bradford/LMS-Adaption kaeme mit dem LUT-/Grading-Ausbau. */
                c *= vec3(1.0 + 0.15 * vbtt.z, 1.0 + 0.10 * vbtt.w, 1.0 - 0.15 * vbtt.z);

                /* --- Tonemap (Domaenenwechsel HDR -> display-referred) --- */
                int op = int(mtdr.y + 0.5);
                if (op == 1) {
                    c = c / (1.0 + c); // Reinhard
                } else if (op == 2) {
                    /* ACES-Fit (Narkowicz) */
                    c = clamp((c * (2.51 * c + 0.03)) / (c * (2.43 * c + 0.59) + 0.14), 0.0, 1.0);
                }

                /* --- display-referred [0,1] --- */
                c = c * lgsh.y + lgsh.x; // Gain, Lift

                /* Shadows/Midtones/Highlights: Luminanz-gewichtete Multiplikatoren */
                float luma = dot(c, LUMA);
                float ws = 1.0 - smoothstep(0.0, 0.5, luma);
                float wh = smoothstep(0.5, 1.0, luma);
                c *= ws * lgsh.z + (1.0 - ws - wh) * mtdr.x + wh * lgsh.w;

                c = (c - 0.5) * egcs.z + 0.5; // Contrast (Pivot 0.5)
                c += vbtt.y;                  // Brightness (additiv)

                luma = dot(c, LUMA);
                c = mix(vec3(luma), c, egcs.w); // Saturation

                /* Vibrance: verstaerkt v.a. schwach gesaettigte Farben — bereits kraeftige
                   bleiben nahezu unveraendert (deshalb getrennt von Saturation). */
                float sat = max(c.r, max(c.g, c.b)) - min(c.r, min(c.g, c.b));
                c = mix(vec3(luma), c, 1.0 + vbtt.x * (1.0 - clamp(sat, 0.0, 1.0)));

                /* --- Output Transform: Gamma zuletzt (Uniform-Branch haelt neutral exakt) --- */
                if (egcs.y != 1.0) c = pow(max(c, 0.0), vec3(1.0 / egcs.y));

                fragColor = vec4(clamp(c, 0.0, 1.0), 1.0);
            }
            """;

    private ShaderProgram program;

    @Override
    public void init(PostContext context) {
        this.program = new ShaderProgram(
                new Shader(PostProcessor.FULLSCREEN_VERTEX_SHADER, ShaderType.VERTEX),
                new Shader(FRAGMENT_SHADER, ShaderType.FRAGMENT));
        this.program.bind();
        this.program.setUniformi("u_Scene", 0);
        this.program.unbind();
    }

    @Override
    public boolean isActive(PostContext context) {
        return true; // Grading ist immer der erste Pass (neutral = Copy)
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
        if (this.program != null) this.program.dispose();
    }
}
