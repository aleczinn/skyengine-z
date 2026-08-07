package de.skyengine.audio;

import java.nio.ShortBuffer;

/**
 * Dekodier-Quelle für {@link MusicPlayer}: liefert häppchenweise interleavte 16-Bit-Samples.
 * Implementierungen: {@link VorbisMusicStream} (.ogg) und {@link WavMusicStream} (.wav).
 * Alle Aufrufe auf dem Render-Thread.
 */
interface MusicStream {

    /** 1 (Mono) oder 2 (Stereo) — mehr Kanäle lehnen die Implementierungen beim Öffnen ab. */
    int channels();

    int sampleRate();

    /**
     * Dekodiert das nächste Stück in {@code pcm} (ab Position 0 bis zum gesetzten Limit).
     *
     * @return Samples PRO KANAL; 0 = Datei zu Ende (oder defekt)
     */
    int read(ShortBuffer pcm);

    /** Setzt zurück an den Anfang (Loop-Pfad). */
    void seekStart();

    void close();
}
