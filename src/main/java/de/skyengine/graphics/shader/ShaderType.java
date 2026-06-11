package de.skyengine.graphics.shader;

import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL32;

public enum ShaderType {

    VERTEX(GL20.GL_VERTEX_SHADER),
    FRAGMENT(GL20.GL_FRAGMENT_SHADER),
    GEOMETRY(GL32.GL_GEOMETRY_SHADER);

    private final int type;

    ShaderType(int type) {
        this.type = type;
    }

    public int getType() {
        return type;
    }
}
