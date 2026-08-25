package de.skyengine.audio;

import de.skyengine.core.resource.DirectoryResourceSource;
import de.skyengine.core.resource.ResourceManager;
import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryUtil;

import java.io.IOException;
import java.nio.ShortBuffer;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class MusicAssetsTest {

    private static final List<String> TRACKS = List.of(
            "celestial-scott-buckley.ogg",
            "the-long-dark-scott-buckley.ogg",
            "unraveling-scott-buckley.ogg",
            "wildflowers-scott-buckley.ogg"
    );

    @Test
    void resourcePlaylistFindsAndDecodesEveryBundledMusicTrack() throws IOException {
        ResourceManager resources = new ResourceManager(new DirectoryResourceSource(
                "test-default", Path.of("src/main/resources/game"), true));
        List<MusicTrack> tracks = SoundManager.loadMusicTracks(resources);

        assertEquals(TRACKS, tracks.stream()
                .map(track -> track.name().substring(track.name().lastIndexOf('/') + 1))
                .toList());
        for (MusicTrack track : tracks) {
            MusicStream stream = VorbisMusicStream.open(track);
            assertTrue(stream != null, track.name());
            try {
                assertEquals(2, stream.channels(), track.name());
                assertEquals(44_100, stream.sampleRate(), track.name());
                ShortBuffer pcm = MemoryUtil.memAllocShort(8_192);
                try {
                    assertTrue(stream.read(pcm) > 0, track.name());
                } finally {
                    MemoryUtil.memFree(pcm);
                }
            } finally {
                stream.close();
            }
        }
    }
}
