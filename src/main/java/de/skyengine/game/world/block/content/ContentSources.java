package de.skyengine.game.world.block.content;

import java.util.ArrayList;
import java.util.List;

/**
 * Registry der aktiven Inhaltsquellen. Die Engine registriert ihre eigene zuerst; Mods/Packs
 * fügen vor dem Bootstrap weitere hinzu. Spätere Quellen überschreiben gleichnamige Inhalte.
 */
public final class ContentSources {

    private static final List<ContentSource> SOURCES = new ArrayList<>();

    public static void register(ContentSource source) {
        SOURCES.add(source);
    }

    public static List<ContentSource> all() {
        return SOURCES;
    }

    public static void clear() {
        SOURCES.clear();
    }

    private ContentSources() {}
}
