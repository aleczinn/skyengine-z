package de.skyengine.graphics.gui.screens;

import de.skyengine.core.file.GameDirectory;
import de.skyengine.graphics.color.Color4;
import de.skyengine.graphics.gui.GuiManager;
import de.skyengine.graphics.gui.GuiScreen;
import de.skyengine.graphics.gui.layout.Anchor;
import de.skyengine.graphics.gui.layout.VStack;
import de.skyengine.graphics.gui.widget.Button;
import de.skyengine.graphics.gui.widget.Label;
import de.skyengine.graphics.gui.widget.Spacer;

import java.io.File;

/**
 * Ressourcenpakete — PLATZHALTER: listet den Ordner {@code resourcepacks/} im Spiel-Root,
 * lädt aber noch nichts (kein Pack-System; Texturen kommen fest aus dem Classpath).
 * Der Ordner wird beim Öffnen angelegt, damit klar ist, wo Pakete später hingehören.
 */
public final class GuiResourcePacks extends GuiScreen {

    private static final Color4 HINT_COLOR = new Color4(0.7f, 0.7f, 0.7f, 1f);
    private static final int MAX_LISTED = 8;

    public GuiResourcePacks(GuiScreen parent) {
        super(parent);
    }

    @Override
    public boolean doesPausesGame() {
        return this.parent != null && this.parent.doesPausesGame();
    }

    @Override
    public boolean blursBackground() {
        return this.parent != null && this.parent.blursBackground();
    }

    @Override
    public void init(GuiManager gui, float vW, float vH) {
        this.components.clear();

        Label title = new Label("Ressourcenpakete", 14).measure(gui);

        VStack content = new VStack(4);
        File dir = GameDirectory.resolve("resourcepacks");
        if (!dir.exists()) dir.mkdirs();

        /* Ordner + Zips als Kandidaten anzeigen (Laden folgt mit dem Pack-System). */
        File[] entries = dir.listFiles(f -> f.isDirectory() || f.getName().toLowerCase().endsWith(".zip"));
        if (entries == null || entries.length == 0) {
            content.add(new Label("Keine Pakete gefunden.", 9).measure(gui));
        } else {
            for (int i = 0; i < entries.length && i < MAX_LISTED; i++) {
                content.add(new Label("- " + entries[i].getName(), 9).measure(gui));
            }
            if (entries.length > MAX_LISTED) {
                content.add(new Label("... und " + (entries.length - MAX_LISTED) + " weitere", 9).measure(gui));
            }
        }
        content.add(new Spacer(0, 4));
        content.add(new Label("Ordner: " + dir.getPath(), 8, HINT_COLOR, true).measure(gui));
        content.add(new Label("Pakete werden noch nicht geladen (Platzhalter).", 8, HINT_COLOR, true).measure(gui));
        content.add(new Spacer(0, 8));
        content.add(new Button("Fertig", () -> this.goBack(gui)));

        this.components.add(title.anchor(Anchor.TOP_CENTER, 0, titleTop(vH)));
        this.components.add(content.anchor(Anchor.TOP_CENTER, 0, contentTop(vH, content.height())));
    }
}
