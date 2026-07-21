package de.skyengine.graphics.gui.screens;

import de.skyengine.graphics.color.Color4;
import de.skyengine.graphics.gui.GuiManager;
import de.skyengine.graphics.gui.GuiScreen;
import de.skyengine.graphics.gui.layout.VStack;
import de.skyengine.graphics.gui.widget.Button;
import de.skyengine.graphics.gui.widget.Label;

import static de.skyengine.graphics.gui.screens.GuiOptionsMenu.CELL_H;
import static de.skyengine.graphics.gui.screens.GuiOptionsMenu.CELL_W;

/**
 * Sprachauswahl — PLATZHALTER: es gibt noch kein Lokalisierungs-System, alle Texte sind
 * hartkodiertes Deutsch. Der Screen hält den Menüplatz frei; sobald i18n existiert, wird die
 * Liste hier echt.
 */
public final class GuiLanguage extends GuiOptionsScreen {

    private static final Color4 HINT_COLOR = new Color4(0.7f, 0.7f, 0.7f, 1f);

    public GuiLanguage(GuiScreen parent) {
        super(parent);
    }

    @Override
    protected String title() {
        return "Sprache";
    }

    @Override
    protected void buildContent(GuiManager gui, VStack content) {
        /* Einzige (ausgewählte) Sprache — Klick tut nichts, bis es Alternativen gibt. */
        content.add(new Button("> Deutsch (Deutschland) <", CELL_W * 2 + 4, CELL_H, null));
        content.add(new Label("Weitere Sprachen folgen, sobald die Engine übersetzt ist.",
                8, HINT_COLOR, true).measure(gui));
    }
}
