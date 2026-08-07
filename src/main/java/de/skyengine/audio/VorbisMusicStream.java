package de.skyengine.audio;

import de.skyengine.utils.logging.LogManager;
import de.skyengine.utils.logging.Logger;
import org.lwjgl.stb.STBVorbisInfo;
import org.lwjgl.system.MemoryStack;

import java.io.File;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;

import static org.lwjgl.stb.STBVorbis.*;

/** OGG/Vorbis-Streaming über stb_vorbis (arbeitet direkt auf dem Dateipfad, kein Vollladen). */
final class VorbisMusicStream implements MusicStream {

    private static final Logger LOGGER = LogManager.getLogger(VorbisMusicStream.class.getName());

    private long handle; // stb_vorbis*; 0 = geschlossen
    private final int channels;
    private final int sampleRate;

    private VorbisMusicStream(long handle, int channels, int sampleRate) {
        this.handle = handle;
        this.channels = channels;
        this.sampleRate = sampleRate;
    }

    /** Öffnet die Datei; {@code null} = nicht abspielbar (Warnung wurde geloggt). */
    static VorbisMusicStream open(File file) {
        long handle;
        int channels, sampleRate;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer error = stack.mallocInt(1);
            handle = stb_vorbis_open_filename(file.getPath(), error, null);
            if (handle == 0) {
                LOGGER.warning("Musik konnte nicht geöffnet werden: " + file.getName()
                        + " (stb-Fehler " + error.get(0) + ")");
                return null;
            }
            STBVorbisInfo info = STBVorbisInfo.malloc(stack);
            stb_vorbis_get_info(handle, info);
            channels = info.channels();
            sampleRate = info.sample_rate();
        }
        if (channels < 1 || channels > 2) {
            LOGGER.warning("Musik hat " + channels + " Kanäle (nur Mono/Stereo möglich): " + file.getName());
            stb_vorbis_close(handle);
            return null;
        }
        return new VorbisMusicStream(handle, channels, sampleRate);
    }

    @Override
    public int channels() {
        return this.channels;
    }

    @Override
    public int sampleRate() {
        return this.sampleRate;
    }

    @Override
    public int read(ShortBuffer pcm) {
        return stb_vorbis_get_samples_short_interleaved(this.handle, this.channels, pcm);
    }

    @Override
    public void seekStart() {
        stb_vorbis_seek_start(this.handle);
    }

    @Override
    public void close() {
        if (this.handle != 0) {
            stb_vorbis_close(this.handle);
            this.handle = 0;
        }
    }
}
