package de.skyengine.graphics.shader;

import de.skyengine.core.io.IDisposable;
import de.skyengine.graphics.color.Color3;
import de.skyengine.graphics.color.Color4;
import de.skyengine.utils.logging.LogManager;
import de.skyengine.utils.logging.Logger;
import org.joml.*;
import org.lwjgl.opengl.GL20;
import org.lwjgl.system.MemoryStack;

import java.nio.IntBuffer;
import java.util.HashMap;

public class ShaderProgram implements IDisposable {

    private int programId;
    private final Shader[] shaders;

    private final HashMap<String, Integer> attributes;
    private final HashMap<String, Integer> uniforms;

    private final Logger logger = LogManager.getLogger(ShaderProgram.class.getName());

    public static final String UNIFORM_PROJECTION = "u_Projection";
    public static final String UNIFORM_TEXTURE = "u_texture";

    public static final String ATTRIBUTE_POSITION = "a_position";
    public static final String ATTRIBUTE_NORMAL = "a_normal";
    public static final String ATTRIBUTE_COLOR = "a_color";
    public static final String ATTRIBUTE_TEXCOORD = "a_texCoord";

    public static final String OUT_POSITION = "v_position";
    public static final String OUT_NORMAL = "v_normal";
    public static final String OUT_COLOR = "v_color";
    public static final String OUT_TEXCOORD = "v_texCoord";

    public ShaderProgram(Shader... shaders) {
        this.shaders = shaders;

        this.attributes = new HashMap<>();
        this.uniforms = new HashMap<>();

        this.create();
    }

    private void create() {
        this.programId = GL20.glCreateProgram();
        for(Shader shader : this.shaders) {
            GL20.glAttachShader(this.programId, shader.getId());
        }

        GL20.glLinkProgram(this.programId);
        if (GL20.glGetProgrami(this.programId, GL20.GL_LINK_STATUS) == GL20.GL_FALSE) {
            this.logger.fatal("ShaderProgram could not be linked!\n" + GL20.glGetProgramInfoLog(this.programId));
        }

        GL20.glValidateProgram(this.programId);
        if (GL20.glGetProgrami(this.programId, GL20.GL_VALIDATE_STATUS) == GL20.GL_FALSE) {
            this.logger.fatal("ShaderProgram could not be validated!\n" + GL20.glGetProgramInfoLog(this.programId));
        }

        this.fetchAttributes();
        this.fetchUniforms();

        this.logger.debug("create shader programm with id " + this.programId);
    }

    protected void setDefaultUniforms() {}

    public void bind() {
        GL20.glUseProgram(this.programId);
        this.setDefaultUniforms();
    }

    public void unbind() {
        GL20.glUseProgram(0);
    }

    private void fetchAttributes() {
        try(MemoryStack stack = MemoryStack.stackPush()) {
            int numAttributes = GL20.glGetProgrami(this.programId, GL20.GL_ACTIVE_ATTRIBUTES);
            IntBuffer type = stack.mallocInt(1);
            IntBuffer parameter = stack.mallocInt(1);

            for(int i = 0; i < numAttributes; i++) {
                parameter.clear();
                parameter.put(0, 1);
                type.clear();

                String name = GL20.glGetActiveAttrib(this.programId, i, parameter, type);
                int location = GL20.glGetAttribLocation(this.programId, name);

                this.attributes.put(name, location);
            }
        }
    }

    private void fetchUniforms() {
        try(MemoryStack stack = MemoryStack.stackPush()) {
            int numUniforms = GL20.glGetProgrami(this.programId, GL20.GL_ACTIVE_UNIFORMS);
            IntBuffer size = stack.mallocInt(1);
            IntBuffer type = stack.mallocInt(1);

            for(int i = 0; i < numUniforms; i++) {
                size.clear();
                size.put(0, 1);
                type.clear();

                String name = GL20.glGetActiveUniform(this.programId, i, size, type);
                int location = GL20.glGetUniformLocation(this.programId, name);

                this.uniforms.put(name, location);
            }
        }
    }

    public int getAttributeLocation(String name) {
        return this.attributes.getOrDefault(name, -1);
    }

    public int getUniformLocation(String name) {
        return this.uniforms.getOrDefault(name, -1);
    }

    /* --- Overloads mit direkter Location (Hot-Path: erspart den Map-Lookup pro Aufruf;
       Location vorher einmalig über getUniformLocation holen) --- */

    public void setUniformf(int location, float value) {
        GL20.glUniform1f(location, value);
    }

    public void setUniformi(int location, int value) {
        GL20.glUniform1i(location, value);
    }

    public void setUniformVector2f(int location, float x, float y) {
        GL20.glUniform2f(location, x, y);
    }

    public void setUniformVector3f(int location, float x, float y, float z) {
        GL20.glUniform3f(location, x, y, z);
    }

    public void setUniformVector4f(int location, float x, float y, float z, float w) {
        GL20.glUniform4f(location, x, y, z, w);
    }

    public void setUniformMatrix4f(int location, Matrix4f matrix) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            GL20.glUniformMatrix4fv(location, false, matrix.get(stack.mallocFloat(16)));
        }
    }

    public void setUniformf(String location, float value) {
        GL20.glUniform1f(this.getUniformLocation(location), value);
    }

    public void setUniformi(String location, int value) {
        GL20.glUniform1i(this.getUniformLocation(location), value);
    }

    public void setUniformVector2f(String location, Vector2f vector) {
        GL20.glUniform2f(this.getUniformLocation(location), vector.x, vector.y);
    }

    public void setUniformVector2f(String location, float x, float y) {
        GL20.glUniform2f(this.getUniformLocation(location), x, y);
    }

    public void setUniformVector3f(String location, Vector3f vector) {
        GL20.glUniform3f(this.getUniformLocation(location), vector.x, vector.y, vector.z);
    }

    public void setUniformVector3f(String location, float x, float y, float z) {
        GL20.glUniform3f(this.getUniformLocation(location), x, y, z);
    }

    public void setUniformVector4f(String location, Vector4f vector) {
        GL20.glUniform4f(this.getUniformLocation(location), vector.x, vector.y, vector.z, vector.w);
    }

    public void setUniformColor4(String location, Color4 color) {
        GL20.glUniform4f(this.getUniformLocation(location), color.red, color.green, color.blue, color.alpha);
    }

    public void setUniformColor3(String location, Color3 color) {
        GL20.glUniform3f(this.getUniformLocation(location), color.red, color.green, color.blue);
    }

    public void setUniformVector4f(String location, float x, float y, float z, float w) {
        GL20.glUniform4f(this.getUniformLocation(location), x, y, z, w);
    }

    public void setUniformVector2d(String location, Vector2d vector) {
        GL20.glUniform2f(this.getUniformLocation(location), (float) vector.x, (float) vector.y);
    }

    public void setUniformMatrix4f(String location, Matrix4f matrix) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            GL20.glUniformMatrix4fv(this.getUniformLocation(location), false, matrix.get(stack.mallocFloat(16)));
        }
    }

    public void setUniformBoolean(String location, boolean value) {
        GL20.glUniform1f(this.getUniformLocation(location), value ? 1.0F : 0.0F);
    }

    public int getProgramId() {
        return programId;
    }

    public Shader[] getShaders() {
        return shaders;
    }

    public HashMap<String, Integer> getAttributes() {
        return attributes;
    }

    public HashMap<String, Integer> getUniforms() {
        return uniforms;
    }

    @Override
    public void dispose() {
        this.unbind();

        for(Shader shader : this.shaders) {
            GL20.glDetachShader(this.programId, shader.getId());
            shader.dispose();
        }

        GL20.glDeleteProgram(this.programId);
        this.programId = -1;
    }
}
