package de.skyengine.audio;

import de.skyengine.utils.logging.LogManager;
import de.skyengine.utils.logging.Logger;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.UnsupportedAudioFileException;
import java.io.File;
import java.io.IOException;
import java.nio.ShortBuffer;

/**
 * WAV-Streaming über die JDK-Bordmittel ({@code javax.sound.sampled}) — dafür braucht es keine
 * zusätzliche Bibliothek. Die Datei wird auf PCM signed 16 Bit little endian umgesetzt; die
 * Samplerate bleibt, wie sie ist (OpenAL akzeptiert jede). Nicht dekodierbar (z.B. Float-WAV,
 * mehr als zwei Kanäle) → Warnung + {@code null} beim Öffnen.
 */
final class WavMusicStream implements MusicStream {

    private static final Logger LOGGER = LogManager.getLogger(WavMusicStream.class.getName());

    private final File file;
    private AudioInputStream in;
    private final int channels;
    private final int sampleRate;

    /** Rohbytes zwischen Datei und ShortBuffer, einmal angelegt und wiederverwendet. */
    private byte[] bytes;

    private WavMusicStream(File file, AudioInputStream in, int channels, int sampleRate) {
        this.file = file;
        this.in = in;
        this.channels = channels;
        this.sampleRate = sampleRate;
    }

    /** Öffnet die Datei; {@code null} = nicht abspielbar (Warnung wurde geloggt). */
    static WavMusicStream open(File file) {
        AudioInputStream in = openDecoded(file);
        if (in == null) return null;

        AudioFormat format = in.getFormat();
        int channels = format.getChannels();
        int sampleRate = (int) format.getSampleRate();
        if (channels < 1 || channels > 2 || sampleRate <= 0) {
            LOGGER.warning("Musik-Format nicht nutzbar (" + channels + " Kanäle, " + sampleRate
                    + " Hz): " + file.getName());
            close(in);
            return null;
        }
        return new WavMusicStream(file, in, channels, sampleRate);
    }

    /** Öffnet die Datei und schaltet sie — falls nötig — auf PCM signed 16 Bit little endian. */
    private static AudioInputStream openDecoded(File file) {
        AudioInputStream raw = null;
        try {
            raw = AudioSystem.getAudioInputStream(file);
            AudioFormat source = raw.getFormat();
            AudioFormat target = new AudioFormat(AudioFormat.Encoding.PCM_SIGNED,
                    source.getSampleRate(), 16, source.getChannels(),
                    source.getChannels() * 2, source.getSampleRate(), false);
            if (source.matches(target)) return raw;
            if (!AudioSystem.isConversionSupported(target, source)) {
                LOGGER.warning("Musik-Format wird nicht unterstützt (" + source + "): " + file.getName());
                close(raw);
                return null;
            }
            return AudioSystem.getAudioInputStream(target, raw);
        } catch (UnsupportedAudioFileException | IOException | IllegalArgumentException e) {
            LOGGER.warning("Musik konnte nicht geöffnet werden: " + file.getName() + " (" + e + ")");
            close(raw);
            return null;
        }
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
        if (this.in == null) return 0;

        int frameBytes = this.channels * 2;
        int needed = (pcm.remaining() / this.channels) * frameBytes;
        if (needed == 0) return 0;
        if (this.bytes == null || this.bytes.length < needed) this.bytes = new byte[needed];

        /* read() liefert Teilmengen — bis voll oder Dateiende weiterlesen. */
        int filled = 0;
        try {
            while (filled < needed) {
                int read = this.in.read(this.bytes, filled, needed - filled);
                if (read <= 0) break;
                filled += read;
            }
        } catch (IOException e) {
            LOGGER.warning("Musik-Lesefehler in " + this.file.getName() + " (" + e + ") — Lied endet hier.");
            return 0;
        }

        filled -= filled % frameBytes; // angebrochene Frames verwerfen
        if (filled == 0) return 0;

        /* Absolut schreiben: der Vorbis-Pfad lässt die Position ebenfalls auf 0 stehen, und der
           MusicPlayer setzt danach nur das Limit — eine verschobene Position ergäbe einen leeren
           AL-Buffer. */
        for (int i = 0; i < filled; i += 2) {
            pcm.put(i >> 1, (short) ((this.bytes[i] & 0xFF) | (this.bytes[i + 1] << 8)));
        }
        return filled / frameBytes;
    }

    @Override
    public void seekStart() {
        close(this.in);
        this.in = openDecoded(this.file); // null = Stream bleibt stumm, read() liefert 0
    }

    @Override
    public void close() {
        close(this.in);
        this.in = null;
    }

    private static void close(AudioInputStream stream) {
        if (stream == null) return;
        try {
            stream.close();
        } catch (IOException ignored) {
            // Schließen darf die Musik nicht zum Absturz bringen.
        }
    }
}
