package de.skyengine.audio;

/**
 * Mischpult-Kanäle (wie Minecrafts SoundSource): jeder Effekt-/Musik-Sound gehört zu genau
 * einem Kanal, dessen Lautstärke (GameSettings.soundVolumes, 0..100) auf den Source-Gain
 * multipliziert wird — zusätzlich zum Master (Listener-Gain).
 *
 * <p>WEATHER/HOSTILE/FRIENDLY haben noch keine Erzeuger (kein Wetter, keine Mobs). AMBIENT
 * steuert bereits die Unterwasser-Klangkulisse.
 */
public enum SoundCategory {
    MUSIC(50),
    WEATHER(100),
    BLOCKS(100),
    HOSTILE(100),
    FRIENDLY(100),
    PLAYER(100),
    AMBIENT(100),
    UI(100);

    /** Default-Lautstärke 0..100 (Musik wie bisher gedämpft). */
    public final int defaultVolume;

    SoundCategory(int defaultVolume) {
        this.defaultVolume = defaultVolume;
    }

    /** i18n-Key der Slider-Beschriftung ({@code sound.category.<name>}). */
    public String translationKey() {
        return "sound.category." + this.name().toLowerCase(java.util.Locale.ROOT);
    }
}
