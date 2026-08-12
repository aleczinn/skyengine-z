package de.skyengine.graphics.world;

import de.skyengine.game.world.environment.EnvironmentState;
import de.skyengine.graphics.GlDebug;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL31;

import java.nio.FloatBuffer;

/** Gemeinsame std140-Umgebungsdaten für Welt- und Himmelshader (binding 1). */
public final class EnvironmentUbo {

    public static final int BINDING = 1;
    private static final int FLOATS = 5 * 4;
    private final FloatBuffer data = BufferUtils.createFloatBuffer(FLOATS);
    private int buffer;

    public void create() {
        this.buffer = GL15.glGenBuffers();
        GL15.glBindBuffer(GL31.GL_UNIFORM_BUFFER, this.buffer);
        GL15.glBufferData(GL31.GL_UNIFORM_BUFFER, (long) FLOATS * Float.BYTES, GL15.GL_DYNAMIC_DRAW);
        GL30.glBindBufferBase(GL31.GL_UNIFORM_BUFFER, BINDING, this.buffer);
        GL15.glBindBuffer(GL31.GL_UNIFORM_BUFFER, 0);
        GlDebug.labelBuffer(this.buffer, "Environment UBO");
    }

    public void update(EnvironmentState state) {
        this.data.clear();
        this.data.put(state.sunDirection.x).put(state.sunDirection.y).put(state.sunDirection.z)
                .put(state.skyIntensity);
        this.data.put(state.moonDirection.x).put(state.moonDirection.y).put(state.moonDirection.z)
                .put(state.moonPhase);
        this.data.put(state.skyLightColor.x).put(state.skyLightColor.y).put(state.skyLightColor.z)
                .put(state.daylight);
        this.data.put(state.fogColor.x).put(state.fogColor.y).put(state.fogColor.z)
                .put(state.fogDensity);
        this.data.put(state.skyTint.x).put(state.skyTint.y).put(state.skyTint.z)
                .put(state.starIntensity);
        this.data.flip();
        GL15.glBindBuffer(GL31.GL_UNIFORM_BUFFER, this.buffer);
        GL15.glBufferSubData(GL31.GL_UNIFORM_BUFFER, 0, this.data);
        GL15.glBindBuffer(GL31.GL_UNIFORM_BUFFER, 0);
    }

    public void dispose() {
        if (this.buffer == 0) return;
        GL15.glDeleteBuffers(this.buffer);
        this.buffer = 0;
    }
}
