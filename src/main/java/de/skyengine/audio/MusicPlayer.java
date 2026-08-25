package de.skyengine.audio;

import de.skyengine.utils.logging.LogManager;
import de.skyengine.utils.logging.Logger;
import org.lwjgl.openal.AL10;
import org.lwjgl.system.MemoryUtil;

import java.nio.ShortBuffer;
import java.util.Locale;

/**
 * Musik-Streaming (.ogg/.wav, s. {@link MusicStream}) über eine dedizierte, nicht-positionale
 * OpenAL-Source: 3 rotierende Buffer werden pro Frame nachgefüllt ({@link #update()}), bei
 * {@code loop} startet die Datei am Ende von vorn. Stereo bleibt Stereo (Musik wird nicht
 * räumlich abgeschwächt). Alle Aufrufe auf dem Render-Thread (wie der gesamte SoundManager).
 */
final class MusicPlayer {

    /** Samples pro Kanal je AL-Buffer (~0,37 s bei 44,1 kHz) — 3 Buffer ≈ 1,1 s Puffer. */
    private static final int BUFFER_SAMPLES = 16384;
    private static final int BUFFER_COUNT = 3;

    private final Logger logger = LogManager.getLogger(MusicPlayer.class.getName());

    private MusicStream stream; // null = keine Musik offen
    private int source = -1;
    private int[] buffers;
    private ShortBuffer pcm; // wiederverwendeter Dekodier-Puffer (memAlloc)
    private int channels, sampleRate, format;
    private boolean playing, loop;
    /** Pausenmenü: Source steht auf AL_PAUSED, update() muss die Finger davon lassen. */
    private boolean paused;
    /** Stream liefert keine Samples mehr (EOF ohne Loop): nur noch auslaufen lassen, nie neu starten. */
    private boolean exhausted;
    /** Aufeinanderfolgende erfolglose Underrun-Neustarts — Deckel gegen Endlos-Spam. */
    private int restartAttempts;
    private float volume = 1.0F;

    /** Reserviert Source und Streaming-Buffer, bevor der Effekt-Pool alle Sources belegen kann. */
    boolean init() {
        if (this.source != -1 && this.buffers != null) return true;
        this.deleteOpenAlObjects();
        clearAlError();
        int generatedSource = AL10.alGenSources();
        int sourceError = AL10.alGetError();
        if (generatedSource == 0 || sourceError != AL10.AL_NO_ERROR) {
            if (generatedSource != 0) AL10.alDeleteSources(generatedSource);
            this.logger.warning("Musik-Source konnte nicht reserviert werden (AL-Fehler "
                    + sourceError + ").");
            return false;
        }
        this.source = generatedSource;
        AL10.alSourcei(this.source, AL10.AL_SOURCE_RELATIVE, AL10.AL_TRUE);
        AL10.alSource3f(this.source, AL10.AL_POSITION, 0, 0, 0);

        this.buffers = new int[BUFFER_COUNT];
        for (int i = 0; i < BUFFER_COUNT; i++) {
            clearAlError();
            int buffer = AL10.alGenBuffers();
            int bufferError = AL10.alGetError();
            if (buffer == 0 || bufferError != AL10.AL_NO_ERROR) {
                if (buffer != 0) AL10.alDeleteBuffers(buffer);
                this.logger.warning("Musik-Buffer konnten nicht reserviert werden (AL-Fehler "
                        + bufferError + ").");
                this.deleteOpenAlObjects();
                return false;
            }
            this.buffers[i] = buffer;
        }
        if (this.pcm == null) this.pcm = MemoryUtil.memAllocShort(BUFFER_SAMPLES * 2);
        return true;
    }

    /**
     * Startet die Datei (ersetzt laufende Musik). Fehler → Warnung, kein Crash.
     *
     * @return true, wenn wirklich Musik läuft — die Playlist überspringt damit defekte Dateien
     */
    boolean play(MusicTrack file, boolean loop) {
        if (file == null || file.data().length == 0) {
            this.logger.warning("Musik-Ressource ist leer.");
            return false;
        }
        for (int attempt = 0; attempt < 2; attempt++) {
            this.stop();
            if (!this.init()) return false;
            this.stream = openStream(file);
            if (this.stream == null) return false;
            this.channels = this.stream.channels();
            this.sampleRate = this.stream.sampleRate();
            this.format = this.channels == 1 ? AL10.AL_FORMAT_MONO16 : AL10.AL_FORMAT_STEREO16;
            this.loop = loop;
            AL10.alSourcef(this.source, AL10.AL_GAIN, this.volume);

            clearAlError();
            int queued = 0;
            for (int buffer : this.buffers) {
                if (!this.fillAndQueue(buffer)) break;
                queued++;
            }
            int queueError = AL10.alGetError();
            if (queued == 0 || queueError != AL10.AL_NO_ERROR) {
                this.logger.warning(queued == 0
                        ? "Musik-Datei liefert keine Daten: " + file.name()
                        : "Musik konnte nicht gepuffert werden (AL-Fehler " + queueError + "): " + file.name());
                this.stop();
                return false;
            }

            clearAlError();
            AL10.alSourcePlay(this.source);
            int playError = AL10.alGetError();
            int state = AL10.alGetSourcei(this.source, AL10.AL_SOURCE_STATE);
            if (playError == AL10.AL_NO_ERROR && state == AL10.AL_PLAYING) {
                this.playing = true;
                this.logger.info("Musik gestartet: " + file.name() + (loop ? " (Loop)" : ""));
                return true;
            }

            this.logger.warning("Musikstart fehlgeschlagen (Versuch " + (attempt + 1)
                    + ", Source " + this.source + ", State " + state + ", AL-Fehler "
                    + playError + "): " + file.name());
            this.stop();
            this.deleteOpenAlObjects();
        }
        return false;
    }

    /** Wählt den Dekoder nach Dateiendung; {@code null} = Format unbekannt oder Datei defekt. */
    private MusicStream openStream(MusicTrack file) {
        String name = file.name().toLowerCase(Locale.ROOT);
        if (name.endsWith(".ogg")) return VorbisMusicStream.open(file);
        if (name.endsWith(".wav")) return WavMusicStream.open(file);
        this.logger.warning("Musik-Format nicht unterstützt (nur .ogg/.wav): " + file.name());
        return null;
    }

    /** Läuft gerade Musik? Pausiert zählt als „läuft" — die Playlist darf dann nicht weiterrücken. */
    boolean isPlaying() {
        return this.playing;
    }

    /**
     * Hält die Musik an, ohne den Dekodier-Stream zu schließen ({@link #stop} täte das und die
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
                /* Datei zu Ende (ohne Loop) oder Stream defekt: nur noch auslaufen lassen. */
                if (!this.exhausted && this.loop) {
                    this.logger.warning("Musik-Stream liefert trotz Loop keine Daten mehr — Musik läuft aus.");
                }
                this.exhausted = true;
            }
        }
        if (this.exhausted && AL10.alGetSourcei(this.source, AL10.AL_BUFFERS_QUEUED) == 0) {
            this.stop();
            return;
        }

        int state = AL10.alGetSourcei(this.source, AL10.AL_SOURCE_STATE);
        if (state == AL10.AL_PLAYING) {
            this.restartAttempts = 0;
            return;
        }

        /* Underrun (z.B. langer Ladehänger): Source ist ausgelaufen, obwohl frische Daten anliegen.
           AL_BUFFERS_QUEUED zählt auch schon abgespielte Buffer mit — erst queued − processed sagt,
           ob wirklich etwas abzuspielen ist. Greift alSourcePlay wiederholt nicht (z.B. nach
           Gerätewechsel), einmalig warnen und aufgeben statt jeden Frame zu spammen. */
        int pending = AL10.alGetSourcei(this.source, AL10.AL_BUFFERS_QUEUED)
                - AL10.alGetSourcei(this.source, AL10.AL_BUFFERS_PROCESSED);
        if (!this.exhausted && pending > 0) {
            if (this.restartAttempts >= 3) {
                this.logger.warning("Musik-Underrun nicht behebbar (Source-State " + state
                        + ", AL-Fehler " + AL10.alGetError() + ") — Musik gestoppt.");
                this.stop();
                return;
            }
            this.restartAttempts++;
            this.logger.debug("Musik-Underrun — Wiedergabe fortgesetzt.");
            AL10.alSourcePlay(this.source);
        }
    }

    /** Dekodiert das nächste Stück in den Buffer und hängt ihn an; false = Datei zu Ende. */
    private boolean fillAndQueue(int buffer) {
        this.pcm.clear().limit(BUFFER_SAMPLES * this.channels);
        int samples = this.stream.read(this.pcm);
        if (samples == 0 && this.loop) {
            this.stream.seekStart();
            samples = this.stream.read(this.pcm);
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
        if (this.stream != null) {
            this.stream.close();
            this.stream = null;
        }
        this.playing = false;
        this.paused = false;
        this.exhausted = false;
        this.restartAttempts = 0;
    }

    /** Lautstärke der Musik-Source (wirkt zusätzlich zum Master-Gain des Listeners). */
    void setVolume(float gain) {
        this.volume = gain;
        if (this.source != -1) AL10.alSourcef(this.source, AL10.AL_GAIN, gain);
    }

    void dispose() {
        this.stop();
        this.deleteOpenAlObjects();
        if (this.pcm != null) {
            MemoryUtil.memFree(this.pcm);
            this.pcm = null;
        }
    }

    private void deleteOpenAlObjects() {
        if (this.source != -1) {
            AL10.alDeleteSources(this.source);
            this.source = -1;
        }
        if (this.buffers != null) {
            for (int buffer : this.buffers) {
                if (buffer != 0) AL10.alDeleteBuffers(buffer);
            }
            this.buffers = null;
        }
    }

    private static void clearAlError() {
        while (AL10.alGetError() != AL10.AL_NO_ERROR) {
            // OpenAL speichert Fehler pro Context; vor einer geprueften Operation leeren.
        }
    }
}
