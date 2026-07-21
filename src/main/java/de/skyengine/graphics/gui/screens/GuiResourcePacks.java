package de.skyengine.graphics.gui.screens;

import de.skyengine.core.file.GameDirectory;
import de.skyengine.graphics.color.Color4;
import de.skyengine.graphics.gui.GuiManager;
import de.skyengine.graphics.gui.GuiScreen;
import de.skyengine.graphics.gui.layout.VStack;
import de.skyengine.graphics.gui.widget.Label;
import de.skyengine.graphics.gui.widget.Spacer;

import java.io.File;

/**
 * Ressourcenpakete — PLATZHALTER: listet den Ordner {@code resourcepacks/} im Spiel-Root,
 * lädt aber noch nichts (kein Pack-System; Texturen kommen fest aus dem Classpath).
 * Der Ordner wird beim Öffnen angelegt, damit klar ist, wo Pakete später hingehören.
 * Lange Listen scrollen über die {@link GuiOptionsScreen}-Basis.
 */
public final class GuiResourcePacks extends GuiOptionsScreen {

    private static final Color4 HINT_COLOR = new Color4(0.7f, 0.7f, 0.7f, 1f);

    public GuiResourcePacks(GuiScreen parent) {
        super(parent);
    }

    @Override
    protected String title() {
        return "Ressourcenpakete";
    }

    @Override
    protected void buildContent(GuiManager gui, VStack content) {
        File dir = GameDirectory.resolve("resourcepacks");
        if (!dir.exists()) dir.mkdirs();

        /* Ordner + Zips als Kandidaten anzeigen (Laden folgt mit dem Pack-System). */
        File[] entries = dir.listFiles(f -> f.isDirectory() || f.getName().toLowerCase().endsWith(".zip"));
        if (entries == null || entries.length == 0) {
            content.add(new Label("Keine Pakete gefunden.", 9).measure(gui));
        } else {
            for (File entry : entries) {
                content.add(new Label("- " + entry.getName(), 9).measure(gui));
            }
        }
        content.add(new Spacer(0, 4));
        content.add(new Label("Ordner: " + dir.getPath(), 8, HINT_COLOR, true).measure(gui));
        content.add(new Label("Pakete werden noch nicht geladen (Platzhalter).", 8, HINT_COLOR, true).measure(gui));
    }
}
