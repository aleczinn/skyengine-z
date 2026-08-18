package de.skyengine.audio;

/** Eine vollstaendig geladene Musikressource; funktioniert aus Verzeichnissen und ZIP-Packs. */
record MusicTrack(String name, byte[] data) {}
