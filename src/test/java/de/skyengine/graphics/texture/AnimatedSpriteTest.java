package de.skyengine.graphics.texture;

import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class AnimatedSpriteTest {

    @Test
    void interpolatesCurrentFrameAndWrapsToFirst() {
        ByteBuffer black = rgba(0);
        ByteBuffer white = rgba(255);
        AnimatedSprite sprite = new AnimatedSprite(7, new ByteBuffer[]{black, white},
                new int[]{0, 1}, 1, true);
        try {
            assertEquals(128, sprite.advance(0.025).get(0) & 0xFF);
            assertEquals(255, sprite.advance(0.025).get(0) & 0xFF);
            assertEquals(128, sprite.advance(0.025).get(0) & 0xFF);
            assertEquals(0, sprite.advance(0.025).get(0) & 0xFF);
        } finally {
            sprite.dispose();
        }
    }

    private static ByteBuffer rgba(int value) {
        ByteBuffer buffer = MemoryUtil.memAlloc(4);
        for (int i = 0; i < 4; i++) buffer.put(i, (byte) value);
        return buffer;
    }
}
