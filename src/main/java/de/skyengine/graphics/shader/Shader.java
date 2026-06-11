package de.skyengine.graphics.shader;

import de.skyengine.core.file.FileHandle;
import de.skyengine.core.io.IDisposable;
import de.skyengine.utils.logging.LogManager;
import de.skyengine.utils.logging.Logger;
import org.lwjgl.opengl.GL20;

public class Shader implements IDisposable {

    private int id;
    private final String shader;
    private final ShaderType type;

    private final Logger logger = LogManager.getLogger(Shader.class.getName());

    public Shader(String shader, ShaderType type) {
        this.shader = shader;
        this.type = type;

        this.load();
    }

    public Shader(FileHandle file, ShaderType type) {
        this.shader = file.readString();
        this.type = type;

        this.load();
    }

    private void load() {
        this.id = GL20.glCreateShader(this.type.getType());

        GL20.glShaderSource(this.id, this.shader);
        GL20.glCompileShader(this.id);

        if (GL20.glGetShaderi(this.id, GL20.GL_COMPILE_STATUS) == GL20.GL_TRUE) {
            this.logger.debug("Shader with type " + this.type + "_SHADER created.");
        } else {
            this.logger.fatal("Shader cannot be compiled!\n" + GL20.glGetShaderInfoLog(this.id));
        }
    }

    public int getId() {
        return id;
    }

    @Override
    public void dispose() {
        this.logger.debug("delete shader with id " + this.id);

        GL20.glDeleteShader(this.id);
    }
}
