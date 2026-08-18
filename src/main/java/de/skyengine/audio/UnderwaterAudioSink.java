package de.skyengine.audio;

/** Schmale, testbare Ausgabe-Schnittstelle des Unterwasser-Audiocontrollers. */
interface UnderwaterAudioSink {
    void playUnderwaterEnter();
    void playUnderwaterExit();
    void setUnderwaterLoopGain(float gain);
    void playUnderwaterAddition(int rarity);
    void playSwim(float speed);
    void playSplash(float speed);
}
