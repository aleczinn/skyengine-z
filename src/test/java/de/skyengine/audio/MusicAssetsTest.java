package de.skyengine.audio;

import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryUtil;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ShortBuffer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class MusicAssetsTest {

    private static final String[] TRACKS = {
            "celestial-scott-buckley.ogg",
            "the-long-dark-scott-buckley.ogg",
            "unraveling-scott-buckley.ogg",
            "wildflowers-scott-buckley.ogg"
    };

    @Test
    void everyBundledMusicTrackOpensAndDecodesPcm() throws IOException {
        for (String name : TRACKS) {
            String path = "/game/sounds/music/" + name;
            try (InputStream input = MusicAssetsTest.class.getResourceAsStream(path)) {
                assertNotNull(input, path);
                MusicStream stream = VorbisMusicStream.open(new MusicTrack(name, input.readAllBytes()));
                assertNotNull(stream, name);
                try {
                    assertEquals(2, stream.channels(), name);
                    assertEquals(44_100, stream.sampleRate(), name);
                    ShortBuffer pcm = MemoryUtil.memAllocShort(8_192);
                    try {
                        assertTrue(stream.read(pcm) > 0, name);
                    } finally {
                        MemoryUtil.memFree(pcm);
                    }
                } finally {
                    stream.close();
                }
            }
        }
    }
}
