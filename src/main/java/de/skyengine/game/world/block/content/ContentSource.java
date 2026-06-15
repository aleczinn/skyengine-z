package de.skyengine.game.world.block.content;

import java.io.File;

/**
 * Eine Inhaltsquelle (Engine-Resources, Resource-Pack oder Mod-Content). Liefert die
 * Verzeichnisse für Blöcke/Modelle/Blockstates eines Namespaces. Mehrere Quellen werden beim
 * Bootstrap zusammengeführt — der Modding-Einstieg für daten- und codeseitige Erweiterungen.
 */
public interface ContentSource {

    String namespace();

    File blocks();

    File models();

    File blockstates();
}
