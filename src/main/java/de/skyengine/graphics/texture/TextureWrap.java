package de.skyengine.graphics.texture;

import org.lwjgl.opengl.GL14;

public enum TextureWrap {

    CLAMP_TO_EDGE(GL14.GL_CLAMP_TO_EDGE),
    REPEAT(GL14.GL_REPEAT),
    MIRRORED_REPEAT(GL14.GL_MIRRORED_REPEAT);

    private final int glEnum;

    TextureWrap(int glEnum) {
        this.glEnum = glEnum;
    }

    public int getGlEnum() {
        return glEnum;
    }
}