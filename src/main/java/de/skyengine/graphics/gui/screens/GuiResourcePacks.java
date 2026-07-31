package de.skyengine.graphics.gui.screens;

import de.skyengine.core.file.GameDirectory;
import de.skyengine.core.i18n.I18n;
import de.skyengine.graphics.color.Color4;
import de.skyengine.graphics.gui.GuiManager;
import de.skyengine.graphics.gui.GuiScreen;
import de.skyengine.graphics.gui.layout.HStack;
import de.skyengine.graphics.gui.layout.VStack;
import de.skyengine.graphics.gui.widget.Button;
import de.skyengine.graphics.gui.widget.GuiComponent;
import de.skyengine.graphics.gui.widget.Label;
import de.skyengine.graphics.gui.widget.Spacer;
import de.skyengine.utils.logging.LogManager;
import de.skyengine.graphics.gui.GuiText;

import java.io.File;
import java.io.IOException;

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
        return I18n.tr("resourcepacks.title");
    }

    @Override
    protected void buildContent(GuiManager gui, VStack content) {
        File dir = packsDir();

        /* Ordner + Zips als Kandidaten anzeigen (Laden folgt mit dem Pack-System). */
        File[] entries = dir.listFiles(f -> f.isDirectory() || f.getName().toLowerCase().endsWith(".zip"));
        if (entries == null || entries.length == 0) {
            content.add(new Label(I18n.tr("resourcepacks.none"), GuiText.NORMAL).measure(gui));
        } else {
            for (File entry : entries) {
                content.add(new Label("- " + entry.getName(), GuiText.NORMAL).measure(gui));
            }
        }
        content.add(new Spacer(0, 4));
        content.add(new Label(I18n.tr("resourcepacks.folder", dir.getPath()), GuiText.SMALL, HINT_COLOR, true).measure(gui));
        content.add(new Label(I18n.tr("resourcepacks.placeholder"), GuiText.SMALL, HINT_COLOR, true).measure(gui));
    }

    @Override
    protected GuiComponent buildFooter(GuiManager gui) {
        Button open = new Button(I18n.tr("resourcepacks.open_folder"), 150, 20, GuiResourcePacks::openPacksFolder);
        Button done = new Button(I18n.tr("gui.done"), 150, 20, () -> this.goBack(gui));
        return new HStack(6, open, done);
    }

    /** Pack-Ordner im Spiel-Root, wird bei Bedarf angelegt. */
    private static File packsDir() {
        File dir = GameDirectory.resolve("resourcepacks");
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    /** Öffnet den Pack-Ordner im Windows-Explorer (Projekt ist Windows-zentriert, kein AWT). */
    private static void openPacksFolder() {
        try {
            new ProcessBuilder("explorer.exe", packsDir().getAbsolutePath()).start();
        } catch (IOException e) {
            LogManager.getLogger(GuiResourcePacks.class.getName())
                    .warning("Paketordner konnte nicht geöffnet werden: " + e.getMessage());
        }
    }
}
