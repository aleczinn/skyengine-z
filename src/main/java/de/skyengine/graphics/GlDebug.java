package de.skyengine.graphics;

import de.skyengine.utils.logging.LogManager;
import de.skyengine.utils.logging.Logger;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL43;

/**
 * Benennt GL-Objekte für Debug-Werkzeuge (glObjectLabel, KHR_debug) und loggt die
 * ID→Name-Zuordnung. Wichtig: Der NVIDIA-Treiber schreibt Labels NICHT in seine
 * Konsolen-Warnungen („Buffer object 28 …") — die Zuordnung dort läuft über das
 * Debug-Log; die Labels selbst helfen in RenderDoc/Nsight.
 */
public final class GlDebug {

    private static final Logger LOGGER = LogManager.getLogger(GlDebug.class.getName());

    private GlDebug() {}

    public static void labelBuffer(int id, String name) {
        label(GL43.GL_BUFFER, "GL-Buffer", id, name);
    }

    public static void labelTexture(int id, String name) {
        label(GL11.GL_TEXTURE, "GL-Textur", id, name);
    }

    public static void labelVertexArray(int id, String name) {
        label(GL11.GL_VERTEX_ARRAY, "GL-VAO", id, name);
    }

    private static void label(int type, String kind, int id, String name) {
        var caps = GL.getCapabilities();
        if (caps.OpenGL43 || caps.GL_KHR_debug) {
            GL43.glObjectLabel(type, id, name);
        }
        LOGGER.debug(kind + " " + id + " = " + name);
    }
}
