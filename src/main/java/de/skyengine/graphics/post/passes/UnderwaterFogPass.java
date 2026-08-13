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
 * Minecraft-naher Wassernebel als szenenweiter Depth-Post-Pass. Er liegt nach dem
 * Color-Grading (der feste Farbwert bleibt dadurch stabil) und vor AA/Menu-Blur.
 */
public final class UnderwaterFogPass implements PostPass {

    public static final float FOG_START = -8F;
    public static final float FOG_END = 96F;
    public static final float MIN_VISION = 0.25F;

    private static final float WATER_R = 5F / 255F;
    private static final float WATER_G = 5F / 255F;
    private static final float WATER_B = 51F / 255F;

    private static final String FRAGMENT_SHADER = ("""
            #version 460 core

            in vec2 v_uv;
            out vec4 fragColor;

            uniform sampler2D u_Input;
            uniform sampler2D u_Depth;
            uniform mat4 u_InvProjView;
            uniform int u_ReversedDepth;
            uniform float u_FogEnd;
            uniform float u_WaterVision;

            const float FOG_START = %s;
            const vec3 WATER_FOG_BASE = vec3(%s, %s, %s);

            vec3 waterFogColor() {
                /* FogRenderer.computeFogColor (Minecraft 26.2): Water Vision zieht jeden
                   Kanal zur maximal hellen, farbtreuen Variante hoch. */
                float maximum = max(WATER_FOG_BASE.r, max(WATER_FOG_BASE.g, WATER_FOG_BASE.b));
                float nonMaximumScale = 1.0 / clamp(maximum, 0.07, 1.0);
                float maximumScale = 1.0 / maximum;
                vec3 scales = vec3(
                        WATER_FOG_BASE.r == maximum ? maximumScale : nonMaximumScale,
                        WATER_FOG_BASE.g == maximum ? maximumScale : nonMaximumScale,
                        WATER_FOG_BASE.b == maximum ? maximumScale : nonMaximumScale);
                return mix(WATER_FOG_BASE, WATER_FOG_BASE * scales, u_WaterVision);
            }

            void main() {
                vec3 scene = texture(u_Input, v_uv).rgb;
                float depth = texture(u_Depth, v_uv).r;
                bool clearDepth = u_ReversedDepth != 0
                        ? depth <= 0.0000001
                        : depth >= 0.9999999;

                float fog = 1.0;
                if (!clearDepth) {
                    /* Reversed-Z nutzt ClipControl ZERO_TO_ONE; der Legacy-Pfad die
                       klassische OpenGL-NDC-Spanne -1..1. */
                    float clipZ = u_ReversedDepth != 0 ? depth : depth * 2.0 - 1.0;
                    vec4 rel = u_InvProjView * vec4(v_uv * 2.0 - 1.0, clipZ, 1.0);
                    float distanceToCamera = length(rel.xyz / rel.w);
                    fog = clamp((distanceToCamera - FOG_START) / (u_FogEnd - FOG_START), 0.0, 1.0);
                }

                fragColor = vec4(mix(scene, waterFogColor(), fog), 1.0);
            }
            """).formatted(
            Float.toString(FOG_START),
            Float.toString(WATER_R), Float.toString(WATER_G), Float.toString(WATER_B));

    private ShaderProgram program;

    /** CPU-Gegenstueck der Shader-Formel fuer Regressionstests und Diagnose. */
    public static float fogFactor(float distance) {
        return Math.clamp((distance - FOG_START) / (FOG_END - FOG_START), 0F, 1F);
    }

    /** Minecraft: 96 Blöcke multipliziert mit mindestens 25 % Water Vision. */
    public static float fogEnd(float waterVision) {
        return FOG_END * Math.max(MIN_VISION, Math.clamp(waterVision, 0F, 1F));
    }

    /** CPU-Gegenstück von Minecrafts Water-Vision-Farbaufhellung, gepackt als 0xRRGGBB. */
    public static int fogColor(float waterVision) {
        float vision = Math.clamp(waterVision, 0F, 1F);
        float[] base = {WATER_R, WATER_G, WATER_B};
        float maximum = Math.max(base[0], Math.max(base[1], base[2]));
        float nonMaximumScale = 1F / Math.clamp(maximum, 0.07F, 1F);
        float maximumScale = 1F / maximum;
        int color = 0;
        for (float channel : base) {
            float scale = channel == maximum ? maximumScale : nonMaximumScale;
            int value = Math.clamp(Math.round((channel + (channel * scale - channel) * vision) * 255F), 0, 255);
            color = (color << 8) | value;
        }
        return color;
    }

    @Override
    public void init(PostContext context) {
        this.program = new ShaderProgram(
                new Shader(PostProcessor.FULLSCREEN_VERTEX_SHADER, ShaderType.VERTEX),
                new Shader(FRAGMENT_SHADER, ShaderType.FRAGMENT));
        this.program.bind();
        this.program.setUniformi("u_Input", 0);
        this.program.setUniformi("u_Depth", 1);
        this.program.unbind();
    }

    @Override
    public boolean isActive(PostContext context) {
        return context.underwater && context.sceneDepth != 0;
    }

    @Override
    public void execute(PostContext context) {
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, context.targetFbo);
        this.program.bind();
        this.program.setUniformMatrix4f("u_InvProjView", context.invProjView);
        this.program.setUniformi("u_ReversedDepth", context.reversedDepth ? 1 : 0);
        this.program.setUniformf("u_FogEnd", fogEnd(context.waterVision));
        this.program.setUniformf("u_WaterVision", context.waterVision);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, context.input);
        GL13.glActiveTexture(GL13.GL_TEXTURE1);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, context.sceneDepth);
        context.drawFullscreenTriangle();
        this.program.unbind();
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
    }

    @Override
    public void dispose() {
        if (this.program != null) this.program.dispose();
    }
}
