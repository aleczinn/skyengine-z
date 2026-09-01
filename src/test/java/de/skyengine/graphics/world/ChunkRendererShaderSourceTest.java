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

    @Test
    void compositeOverlayDoesNotDiscardAVisibleBaseFragment() {
        String vertex = ChunkRenderer.VERTEX_SOURCE;
        String fragment = ChunkRenderer.FRAGMENT_SOURCE;

        assertTrue(vertex.contains("materialHandle & 0x8000u"));
        assertTrue(vertex.contains("u_CompositeMaterials[materialHandle & 0x7FFFu]"));
        assertTrue(fragment.contains("bool baseVisible = color.a >= u_AlphaCutoff"));
        assertTrue(fragment.contains("bool overlayVisible = overlay.a >= u_AlphaCutoff"));
        assertTrue(fragment.contains("if (overlayVisible)"));
        assertTrue(fragment.contains("} else if (!baseVisible)"));
        assertTrue(fragment.contains("texture(u_NormalTextures, selectedTexCoord)"));
        assertFalse(fragment.contains("if (overlay.a < u_AlphaCutoff) discard"));
    }
}
