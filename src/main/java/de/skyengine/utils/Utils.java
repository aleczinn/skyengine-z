package de.skyengine.utils;

import org.lwjgl.opengl.GL11;

public class Utils {

    public static void enableWireframe() {
        GL11.glPolygonMode(GL11.GL_FRONT_AND_BACK, GL11.GL_LINE);
    }

    public static void disableWireframe() {
        GL11.glPolygonMode(GL11.GL_FRONT_AND_BACK, GL11.GL_FILL);
    }

    public static void setWireframe(boolean value) {
        if(value) {
            enableWireframe();
        } else {
            disableWireframe();
        }
    }
}
