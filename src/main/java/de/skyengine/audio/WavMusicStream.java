package de.skyengine.audio;

import de.skyengine.utils.logging.LogManager;
import de.skyengine.utils.logging.Logger;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.UnsupportedAudioFileException;
import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ShortBuffer;

/** WAV-Streaming aus einer Speicherressource, einschliesslich Ressourcen aus ZIP-Packs. */
final class WavMusicStream implements MusicStream {
    private static final Logger LOGGER = LogManager.getLogger(WavMusicStream.class.getName());

    private final MusicTrack track;
    private AudioInputStream in;
    private final int channels;
    private final int sampleRate;
    private byte[] bytes;

    private WavMusicStream(MusicTrack track, AudioInputStream in, int channels, int sampleRate) {
        this.track = track;
        this.in = in;
        this.channels = channels;
        this.sampleRate = sampleRate;
    }

    static WavMusicStream open(MusicTrack track) {
        AudioInputStream in = openDecoded(track);
        if (in == null) return null;
        AudioFormat format = in.getFormat();
        int channels = format.getChannels();
        int sampleRate = (int) format.getSampleRate();
        if (channels < 1 || channels > 2 || sampleRate <= 0) {
            LOGGER.warning("Musik-Format nicht nutzbar (" + channels + " Kanaele, " + sampleRate
                    + " Hz): " + track.name());
            close(in);
            return null;
        }
        return new WavMusicStream(track, in, channels, sampleRate);
    }

    private static AudioInputStream openDecoded(MusicTrack track) {
        AudioInputStream raw = null;
        try {
            raw = AudioSystem.getAudioInputStream(new BufferedInputStream(
                    new ByteArrayInputStream(track.data())));
            AudioFormat source = raw.getFormat();
            AudioFormat target = new AudioFormat(AudioFormat.Encoding.PCM_SIGNED,
                    source.getSampleRate(), 16, source.getChannels(), source.getChannels() * 2,
                    source.getSampleRate(), false);
            if (source.matches(target)) return raw;
            if (!AudioSystem.isConversionSupported(target, source)) {
                LOGGER.warning("Musik-Format wird nicht unterstuetzt (" + source + "): " + track.name());
                close(raw);
                return null;
            }
            return AudioSystem.getAudioInputStream(target, raw);
        } catch (UnsupportedAudioFileException | IOException | IllegalArgumentException e) {
            LOGGER.warning("Musik konnte nicht geoeffnet werden: " + track.name() + " (" + e + ")");
            close(raw);
            return null;
        }
    }

    @Override public int channels() { return this.channels; }
    @Override public int sampleRate() { return this.sampleRate; }

    @Override
    public int read(ShortBuffer pcm) {
        if (this.in == null) return 0;
        int frameBytes = this.channels * 2;
        int needed = (pcm.remaining() / this.channels) * frameBytes;
        if (needed == 0) return 0;
        if (this.bytes == null || this.bytes.length < needed) this.bytes = new byte[needed];
        int filled = 0;
        try {
            while (filled < needed) {
                int read = this.in.read(this.bytes, filled, needed - filled);
                if (read <= 0) break;
                filled += read;
            }
        } catch (IOException e) {
            LOGGER.warning("Musik-Lesefehler in " + this.track.name() + " (" + e + ")");
            return 0;
        }
        filled -= filled % frameBytes;
        if (filled == 0) return 0;
        for (int i = 0; i < filled; i += 2) {
            pcm.put(i >> 1, (short) ((this.bytes[i] & 0xFF) | (this.bytes[i + 1] << 8)));
        }
        return filled / frameBytes;
    }

    @Override
    public void seekStart() {
        close(this.in);
        this.in = openDecoded(this.track);
    }

    @Override
    public void close() {
        close(this.in);
        this.in = null;
    }

    private static void close(AudioInputStream stream) {
        if (stream == null) return;
        try { stream.close(); } catch (IOException ignored) {}
    }
}
