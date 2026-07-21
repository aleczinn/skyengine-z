package de.skyengine.audio;

/**
 * Mischpult-Kanäle (wie Minecrafts SoundSource): jeder Effekt-/Musik-Sound gehört zu genau
 * einem Kanal, dessen Lautstärke (GameSettings.soundVolumes, 0..100) auf den Source-Gain
 * multipliziert wird — zusätzlich zum Master (Listener-Gain).
 *
 * <p>WEATHER/HOSTILE/FRIENDLY/AMBIENT haben noch keine Erzeuger (kein Wetter, keine Mobs) —
 * die Regler existieren schon und greifen, sobald entsprechende Systeme Sounds abspielen.
 */
public enum SoundCategory {
    MUSIC("Musik", 50),
    WEATHER("Wetter", 100),
    BLOCKS("Blöcke", 100),
    HOSTILE("Feindliche Kreaturen", 100),
    FRIENDLY("Freundliche Kreaturen", 100),
    PLAYER("Spieler", 100),
    AMBIENT("Atmosphäre", 100),
    UI("Benutzeroberfläche", 100);

    /** Deutsche Slider-Beschriftung. */
    public final String label;
    /** Default-Lautstärke 0..100 (Musik wie bisher gedämpft). */
    public final int defaultVolume;

    SoundCategory(String label, int defaultVolume) {
        this.label = label;
        this.defaultVolume = defaultVolume;
    }
}
