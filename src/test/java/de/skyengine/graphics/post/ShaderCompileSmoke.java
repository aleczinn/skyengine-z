package de.skyengine.graphics.post;

import de.skyengine.graphics.post.passes.AntiAliasingPass;
import de.skyengine.graphics.shader.Shader;
import de.skyengine.graphics.shader.ShaderProgram;
import de.skyengine.graphics.shader.ShaderType;
import de.skyengine.graphics.shaderpack.ShaderPack;
import de.skyengine.graphics.shaderpack.ShaderPackLoader;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;

/**
 * Treiberseitiger Smoke-Test fuer die Shader-Pipeline. Normale Unit-Tests parsen nur die
 * Ressourcen; GLSL-Syntax-, Interface- und Linkfehler sieht ausschliesslich ein echter
 * OpenGL-Kontext.
 */
public final class ShaderCompileSmoke {
    private static final String[][] GEOMETRY_PROGRAMS = {
            {"terrain_vertex", "terrain_fragment"},
            {"sky_vertex", "sky_fragment"}
    };

    private static final String[] FULLSCREEN_PROGRAMS = {
            "air_vl", "water_vl", "fluid_fog",
            "bloom_downsample", "bloom_blur", "bloom_upsample", "bloom_composite",
            "color_grading"
    };

    private ShaderCompileSmoke() {
    }

    public static void main(String[] args) {
        GLFWErrorCallback errorCallback = GLFWErrorCallback.createPrint(System.err);
        GLFW.glfwSetErrorCallback(errorCallback);
        if (!GLFW.glfwInit()) throw new IllegalStateException("GLFW konnte nicht initialisiert werden");

        long window = 0L;
        try {
            GLFW.glfwDefaultWindowHints();
            GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
            GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE, GLFW.GLFW_OPENGL_CORE_PROFILE);
            GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_FORWARD_COMPAT, GLFW.GLFW_TRUE);
            GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, 4);
            GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 6);
            window = GLFW.glfwCreateWindow(16, 16, "SkyEngine Shader Test", 0L, 0L);
            if (window == 0L) throw new IllegalStateException("Versteckter OpenGL-Kontext konnte nicht erstellt werden");
            GLFW.glfwMakeContextCurrent(window);
            GL.createCapabilities();

            System.out.println("GL: " + GL11.glGetString(GL11.GL_RENDERER)
                    + " / " + GL11.glGetString(GL11.GL_VERSION));
            ShaderPack pack = new ShaderPackLoader().load("vibrant_visuals");

            for (String[] pair : GEOMETRY_PROGRAMS) {
                compile(pair[0] + "+" + pair[1],
                        new Shader(pack.program(pair[0]), ShaderType.VERTEX),
                        new Shader(pack.program(pair[1]), ShaderType.FRAGMENT));
            }
            compile("water_shadow",
                    new Shader(pack.program("water_shadow_vertex"), ShaderType.VERTEX),
                    new Shader(pack.program("water_shadow_tess_control"), ShaderType.TESS_CONTROL),
                    new Shader(pack.program("water_shadow_tess_evaluation"), ShaderType.TESS_EVALUATION),
                    new Shader(pack.program("water_shadow_fragment"), ShaderType.FRAGMENT));
            compile("sky_sh_compute", new Shader(pack.program("sky_sh_compute"), ShaderType.COMPUTE));
            for (String fragment : FULLSCREEN_PROGRAMS) {
                compile(fragment,
                        new Shader(PostProcessor.FULLSCREEN_VERTEX_SHADER, ShaderType.VERTEX),
                        new Shader(pack.program(fragment), ShaderType.FRAGMENT));
            }

            /* Die TAA-/CAS-Programme sind Java-Strings und daher nicht Teil des Pack-Manifests. */
            PostContext context = new PostContext();
            context.create(16, 16);
            AntiAliasingPass antiAliasing = new AntiAliasingPass(AntiAliasingPass.Stage.TEMPORAL);
            try {
                antiAliasing.init(context);
            } finally {
                antiAliasing.dispose();
                context.dispose();
            }
            System.out.println("SHADER OK");
        } finally {
            if (window != 0L) GLFW.glfwDestroyWindow(window);
            GLFW.glfwTerminate();
            GLFW.glfwSetErrorCallback(null);
            errorCallback.free();
        }
    }

    private static void compile(String name, Shader... shaders) {
        ShaderProgram program = null;
        try {
            program = new ShaderProgram(shaders);
            System.out.println("  [OK] " + name);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Shader-Programm fehlgeschlagen: " + name, e);
        } finally {
            if (program != null) program.dispose();
        }
    }
}
