package de.skyengine.graphics.texture;

import org.lwjgl.opengl.GL11;

public enum TextureFilter {

    NEAREST(GL11.GL_NEAREST),
    LINEAR(GL11.GL_LINEAR),

    MIPMAP(GL11.GL_LINEAR_MIPMAP_LINEAR);

    private final int glEnum;

    TextureFilter(int glEnum) {
        this.glEnum = glEnum;
    }

    public int getGlEnum() {
        return glEnum;
    }

    public boolean isMipMap() {
        return this.glEnum != GL11.GL_NEAREST && this.glEnum != GL11.GL_LINEAR;
    }
}
