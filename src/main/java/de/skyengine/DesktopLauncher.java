package de.skyengine;

import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;

public class DesktopLauncher {

    public static void main(String[] args) {
        if (!GLFW.glfwInit()) throw new IllegalStateException("GLFW init failed");

        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, 4);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 6);
        GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE, GLFW.GLFW_OPENGL_CORE_PROFILE);

        long window = GLFW.glfwCreateWindow(1280, 720, "SkyEngine - Z", 0L, 0L);
        if (window == 0L) throw new IllegalStateException("Window creation failed");

        GLFW.glfwMakeContextCurrent(window);
        GL.createCapabilities();

        System.out.println("OpenGL: " + GL11.glGetString(GL11.GL_VERSION));

        while (!GLFW.glfwWindowShouldClose(window)) {
            GL11.glClearColor(0.5f, 0.8f, 1.0f, 1.0f);
            GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);
            GLFW.glfwSwapBuffers(window);
            GLFW.glfwPollEvents();
        }

        GLFW.glfwDestroyWindow(window);
        GLFW.glfwTerminate();
    }
}