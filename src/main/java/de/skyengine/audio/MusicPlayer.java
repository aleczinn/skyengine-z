package de.skyengine.audio;

import de.skyengine.utils.logging.LogManager;
import de.skyengine.utils.logging.Logger;
import org.lwjgl.openal.AL10;
import org.lwjgl.stb.STBVorbisInfo;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import java.io.File;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;

import static org.lwjgl.stb.STBVorbis.*;

/**
 * OGG-Musik-Streaming über eine dedizierte, nicht-positionale OpenAL-Source: 3 rotierende
 * Buffer werden pro Frame nachgefüllt ({@link #update()}), bei {@code loop} startet die Datei
 * am Ende von vorn. Stereo bleibt Stereo (Musik wird nicht räumlich abgeschwächt).
 * Alle Aufrufe auf dem Render-Thread (wie der gesamte SoundManager).
 */
final class MusicPlayer {

    /** Samples pro Kanal je AL-Buffer (~0,37 s bei 44,1 kHz) — 3 Buffer ≈ 1,1 s Puffer. */
    private static final int BUFFER_SAMPLES = 16384;
    private static final int BUFFER_COUNT = 3;

    private final Logger logger = LogManager.getLogger(MusicPlayer.class.getName());

    private long vorbisHandle; // stb_vorbis*; 0 = keine Musik offen
    private int source = -1;
    private int[] buffers;
    private ShortBuffer pcm; // wiederverwendeter Dekodier-Puffer (memAlloc)
    private int channels, sampleRate, format;
    private boolean playing, loop;
    /** Pausenmenü: Source steht auf AL_PAUSED, update() muss die Finger davon lassen. */
    private boolean paused;
    private float volume = 1.0F;

    /** Startet die Datei (ersetzt laufende Musik). Fehler → Warnung, kein Crash. */
    void play(File file, boolean loop) {
        this.stop();
        if (!file.exists()) {
            this.logger.warning("Musik-Datei fehlt: " + file.getPath());
            return;
        }

        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer error = stack.mallocInt(1);
            this.vorbisHandle = stb_vorbis_open_filename(file.getPath(), error, null);
            if (this.vorbisHandle == 0) {
                this.logger.warning("Musik konnte nicht geöffnet werden: " + file.getName() + " (stb-Fehler " + error.get(0) + ")");
                return;
            }
            STBVorbisInfo info = STBVorbisInfo.malloc(stack);
            stb_vorbis_get_info(this.vorbisHandle, info);
            this.channels = info.channels();
            this.sampleRate = info.sample_rate();
        }
        this.format = this.channels == 1 ? AL10.AL_FORMAT_MONO16 : AL10.AL_FORMAT_STEREO16;
        this.loop = loop;

        if (this.source == -1) {
            this.source = AL10.alGenSources();
            AL10.alSourcei(this.source, AL10.AL_SOURCE_RELATIVE, AL10.AL_TRUE);
            AL10.alSource3f(this.source, AL10.AL_POSITION, 0, 0, 0);
        }
        AL10.alSourcef(this.source, AL10.AL_GAIN, this.volume);
        if (this.buffers == null) {
            this.buffers = new int[BUFFER_COUNT];
            for (int i = 0; i < BUFFER_COUNT; i++) this.buffers[i] = AL10.alGenBuffers();
        }
        if (this.pcm == null) {
            this.pcm = MemoryUtil.memAllocShort(BUFFER_SAMPLES * 2); // reicht für Stereo
        }

        for (int buffer : this.buffers) {
            if (!this.fillAndQueue(buffer)) break; // sehr kurze Datei: weniger Buffer queuen
        }
        AL10.alSourcePlay(this.source);
        this.playing = true;
        this.logger.info("Musik gestartet: " + file.getName() + (loop ? " (Loop)" : ""));
    }

    /**
     * Hält die Musik an, ohne den vorbis-Handle zu schließen ({@link #stop} täte das und die
     * Musik startete beim Fortsetzen von vorn).
     */
    void pause() {
        if (!this.playing || this.paused || this.source == -1) return;
        AL10.alSourcePause(this.source);
        this.paused = true;
    }

    /** Gegenstück zu {@link #pause}; setzt an derselben Stelle fort. */
    void resume() {
        if (!this.paused) return;
        this.paused = false;
        if (this.playing && this.source != -1) AL10.alSourcePlay(this.source);
    }

    /** Pro Frame: abgespielte Buffer nachfüllen und wieder anhängen; Underrun neu starten. */
    void update() {
        /* Pausiert NICHTS tun: die Underrun-Prüfung unten sieht sonst AL_PAUSED != AL_PLAYING
           und würde die Musik im nächsten Frame selbst wieder anwerfen. Nachfüllen ist auch
           nicht nötig — eine pausierte Source verarbeitet keine Buffer. */
        if (!this.playing || this.paused) return;

        int processed = AL10.alGetSourcei(this.source, AL10.AL_BUFFERS_PROCESSED);
        for (int i = 0; i < processed; i++) {
            int buffer = AL10.alSourceUnqueueBuffers(this.source);
            if (!this.fillAndQueue(buffer)) {
                /* Datei zu Ende (ohne Loop): Buffer auslaufen lassen, dann stoppen. */
                if (AL10.alGetSourcei(this.source, AL10.AL_BUFFERS_QUEUED) == 0) {
                    this.stop();
                    return;
                }
            }
        }

        /* Underrun (z.B. langer Ladehänger): Source ist ausgelaufen, obwohl Daten anliegen. */
        if (AL10.alGetSourcei(this.source, AL10.AL_SOURCE_STATE) != AL10.AL_PLAYING
                && AL10.alGetSourcei(this.source, AL10.AL_BUFFERS_QUEUED) > 0) {
            this.logger.debug("Musik-Underrun — Wiedergabe fortgesetzt.");
            AL10.alSourcePlay(this.source);
        }
    }

    /** Dekodiert das nächste Stück in den Buffer und hängt ihn an; false = Datei zu Ende. */
    private boolean fillAndQueue(int buffer) {
        this.pcm.clear().limit(BUFFER_SAMPLES * this.channels);
        int samples = stb_vorbis_get_samples_short_interleaved(this.vorbisHandle, this.channels, this.pcm);
        if (samples == 0 && this.loop) {
            stb_vorbis_seek_start(this.vorbisHandle);
            samples = stb_vorbis_get_samples_short_interleaved(this.vorbisHandle, this.channels, this.pcm);
        }
        if (samples == 0) return false;

        this.pcm.limit(samples * this.channels);
        AL10.alBufferData(buffer, this.format, this.pcm, this.sampleRate);
        AL10.alSourceQueueBuffers(this.source, buffer);
        return true;
    }

    void stop() {
        if (this.source != -1) {
            AL10.alSourceStop(this.source);
            int queued = AL10.alGetSourcei(this.source, AL10.AL_BUFFERS_QUEUED);
            for (int i = 0; i < queued; i++) AL10.alSourceUnqueueBuffers(this.source);
        }
        if (this.vorbisHandle != 0) {
            stb_vorbis_close(this.vorbisHandle);
            this.vorbisHandle = 0;
        }
        this.playing = false;
        this.paused = false;
    }

    /** Lautstärke der Musik-Source (wirkt zusätzlich zum Master-Gain des Listeners). */
    void setVolume(float gain) {
        this.volume = gain;
        if (this.source != -1) AL10.alSourcef(this.source, AL10.AL_GAIN, gain);
    }

    void dispose() {
        this.stop();
        if (this.source != -1) {
            AL10.alDeleteSources(this.source);
            this.source = -1;
        }
        if (this.buffers != null) {
            for (int buffer : this.buffers) AL10.alDeleteBuffers(buffer);
            this.buffers = null;
        }
        if (this.pcm != null) {
            MemoryUtil.memFree(this.pcm);
            this.pcm = null;
        }
    }
}
