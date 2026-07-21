package de.skyengine.graphics.gui.screens;

import de.skyengine.core.i18n.I18n;
import de.skyengine.core.settings.GameSettings;
import de.skyengine.graphics.gui.GuiManager;
import de.skyengine.graphics.gui.GuiScreen;
import de.skyengine.graphics.gui.layout.VStack;
import de.skyengine.graphics.gui.widget.Button;

import static de.skyengine.graphics.gui.screens.GuiOptionsMenu.CELL_H;
import static de.skyengine.graphics.gui.screens.GuiOptionsMenu.CELL_W;

/**
 * Sprachauswahl: ein Button je Sprachdatei unter {@code game/lang/}, Name immer nativ
 * (MC-Stil — die eigene Muttersprache bleibt auch bei fremder aktiver Sprache lesbar).
 * Klick wechselt sofort: Sprache laden, speichern, Screen re-initen (alle Texte entstehen
 * in {@code init()} — Neustart unnötig).
 */
public final class GuiLanguage extends GuiOptionsScreen {

    public GuiLanguage(GuiScreen parent) {
        super(parent);
    }

    @Override
    protected String title() {
        return I18n.tr("language.title");
    }

    @Override
    protected void buildContent(GuiManager gui, VStack content) {
        for (I18n.Language language : I18n.available()) {
            boolean selected = language.code().equals(I18n.code());
            String label = selected ? "> " + language.nativeName() + " <" : language.nativeName();
            content.add(new Button(label, CELL_W * 2 + 4, CELL_H, selected ? null : () -> {
                GameSettings settings = GameSettings.get();
                settings.language = language.code();
                settings.save();
                I18n.load(language.code());
                gui.relayoutCurrent();
            }));
        }
    }
}
