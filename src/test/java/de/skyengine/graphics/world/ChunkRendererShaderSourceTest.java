package de.skyengine.graphics.world;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ChunkRendererShaderSourceTest {

    @Test
    void tintUnpackDoesNotUseReservedPackedQualifierAsIdentifier() {
        String source = ChunkRenderer.FRAGMENT_SOURCE;

        assertTrue(source.contains("vec3 unpackTint(uint packedColor)"));
        assertFalse(source.contains("vec3 unpackTint(uint packed)"));
        assertFalse(source.matches("(?s).*\\b(?:uint|int|float|vec[234])\\s+packed\\b.*"));
    }
}
