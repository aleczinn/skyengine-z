package de.skyengine.graphics.gui.screens;

import de.skyengine.graphics.color.Color4;
import de.skyengine.graphics.gui.GuiManager;
import de.skyengine.graphics.gui.GuiScreen;
import de.skyengine.graphics.gui.layout.Anchor;
import de.skyengine.graphics.gui.layout.VStack;
import de.skyengine.graphics.gui.widget.Button;
import de.skyengine.graphics.gui.widget.Label;
import de.skyengine.graphics.gui.widget.Spacer;

import static de.skyengine.graphics.gui.screens.GuiOptionsMenu.CELL_H;
import static de.skyengine.graphics.gui.screens.GuiOptionsMenu.CELL_W;

/**
 * Sprachauswahl — PLATZHALTER: es gibt noch kein Lokalisierungs-System, alle Texte sind
 * hartkodiertes Deutsch. Der Screen hält den Menüplatz frei; sobald i18n existiert, wird die
 * Liste hier echt.
 */
public final class GuiLanguage extends GuiScreen {

    private static final Color4 HINT_COLOR = new Color4(0.7f, 0.7f, 0.7f, 1f);

    public GuiLanguage(GuiScreen parent) {
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

        Label title = new Label("Sprache", 14).measure(gui);
        /* Einzige (ausgewählte) Sprache — Klick tut nichts, bis es Alternativen gibt. */
        Button german = new Button("> Deutsch (Deutschland) <", CELL_W * 2 + 4, CELL_H, null);
        Label hint = new Label("Weitere Sprachen folgen, sobald die Engine übersetzt ist.",
                8, HINT_COLOR, true).measure(gui);
        Button done = new Button("Fertig", () -> this.goBack(gui));

        VStack content = new VStack(4,
                german,
                hint,
                new Spacer(0, 8),
                done);
        this.components.add(title.anchor(Anchor.TOP_CENTER, 0, titleTop(vH)));
        this.components.add(content.anchor(Anchor.TOP_CENTER, 0, contentTop(vH, content.height())));
    }
}
