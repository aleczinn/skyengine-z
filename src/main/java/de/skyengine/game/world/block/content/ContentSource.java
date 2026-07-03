package de.skyengine.game.world.block.content;

import java.io.File;

/**
 * Eine Inhaltsquelle (Engine-Resources, Resource-Pack oder Mod-Content). Liefert die
 * Verzeichnisse für Blöcke und Modelle eines Namespaces. Die Block-Datei enthält neben der
 * Definition auch ihre {@code variants}/{@code multipart}-Render-Sektion (kein eigener
 * blockstates-Ordner mehr). Mehrere Quellen werden beim Bootstrap zusammengeführt — der
 * Modding-Einstieg für daten- und codeseitige Erweiterungen.
 */
public interface ContentSource {

    String namespace();

    File blocks();

    File models();
}
