package de.skyengine.audio;

import de.skyengine.utils.logging.LogManager;
import de.skyengine.utils.logging.Logger;
import org.lwjgl.stb.STBVorbisInfo;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;

import static org.lwjgl.stb.STBVorbis.*;

/** OGG/Vorbis-Streaming aus dem Speicher, damit auch ZIP-Ressourcen ohne Extraktion laufen. */
final class VorbisMusicStream implements MusicStream {
    private static final Logger LOGGER = LogManager.getLogger(VorbisMusicStream.class.getName());

    private long handle;
    private ByteBuffer encoded;
    private final int channels;
    private final int sampleRate;

    private VorbisMusicStream(long handle, ByteBuffer encoded, int channels, int sampleRate) {
        this.handle = handle;
        this.encoded = encoded;
        this.channels = channels;
        this.sampleRate = sampleRate;
    }

    static VorbisMusicStream open(MusicTrack track) {
        ByteBuffer encoded = MemoryUtil.memAlloc(track.data().length);
        encoded.put(track.data()).flip();
        long handle;
        int channels;
        int sampleRate;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer error = stack.mallocInt(1);
            handle = stb_vorbis_open_memory(encoded, error, null);
            if (handle == 0) {
                LOGGER.warning("Musik konnte nicht geoeffnet werden: " + track.name()
                        + " (stb-Fehler " + error.get(0) + ")");
                MemoryUtil.memFree(encoded);
                return null;
            }
            STBVorbisInfo info = STBVorbisInfo.malloc(stack);
            stb_vorbis_get_info(handle, info);
            channels = info.channels();
            sampleRate = info.sample_rate();
        }
        if (channels < 1 || channels > 2) {
            LOGGER.warning("Musik hat " + channels + " Kanaele (nur Mono/Stereo): " + track.name());
            stb_vorbis_close(handle);
            MemoryUtil.memFree(encoded);
            return null;
        }
        return new VorbisMusicStream(handle, encoded, channels, sampleRate);
    }

    @Override public int channels() { return this.channels; }
    @Override public int sampleRate() { return this.sampleRate; }

    @Override
    public int read(ShortBuffer pcm) {
        return stb_vorbis_get_samples_short_interleaved(this.handle, this.channels, pcm);
    }

    @Override public void seekStart() { stb_vorbis_seek_start(this.handle); }

    @Override
    public void close() {
        if (this.handle != 0) {
            stb_vorbis_close(this.handle);
            this.handle = 0;
        }
        if (this.encoded != null) {
            MemoryUtil.memFree(this.encoded);
            this.encoded = null;
        }
    }
}
