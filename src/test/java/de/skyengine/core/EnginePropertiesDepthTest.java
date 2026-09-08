package de.skyengine.core;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.lwjgl.opengl.GL11C.GL_GEQUAL;
import static org.lwjgl.opengl.GL11C.GL_GREATER;
import static org.lwjgl.opengl.GL11C.GL_LEQUAL;
import static org.lwjgl.opengl.GL11C.GL_LESS;

final class EnginePropertiesDepthTest {

    @Test
    void orEqualDepthFunctionKeepsTheActiveDepthDirection() throws Exception {
        EngineProperties properties = new EngineProperties();

        setInverseDepth(properties, false);
        assertEquals(GL_LESS, properties.baseDepthFunc());
        assertEquals(GL_LEQUAL, properties.orEqualDepthFunc());

        setInverseDepth(properties, true);
        assertEquals(GL_GREATER, properties.baseDepthFunc());
        assertEquals(GL_GEQUAL, properties.orEqualDepthFunc());
    }

    private static void setInverseDepth(EngineProperties properties, boolean value) throws Exception {
        Field field = EngineProperties.class.getDeclaredField("useInverseDepth");
        field.setAccessible(true);
        field.setBoolean(properties, value);
    }
}
