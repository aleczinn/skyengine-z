package de.skyengine.graphics.shader;

import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL32;
import org.lwjgl.opengl.GL40;
import org.lwjgl.opengl.GL43;

public enum ShaderType {

    VERTEX(GL20.GL_VERTEX_SHADER),
    TESS_CONTROL(GL40.GL_TESS_CONTROL_SHADER),
    TESS_EVALUATION(GL40.GL_TESS_EVALUATION_SHADER),
    FRAGMENT(GL20.GL_FRAGMENT_SHADER),
    GEOMETRY(GL32.GL_GEOMETRY_SHADER),
    COMPUTE(GL43.GL_COMPUTE_SHADER);

    private final int type;

    ShaderType(int type) {
        this.type = type;
    }

    public int getType() {
        return type;
    }
}
