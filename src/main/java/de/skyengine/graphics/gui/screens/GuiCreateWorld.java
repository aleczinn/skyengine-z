package de.skyengine.graphics.gui.screens;

import de.skyengine.core.SkyEngine;
import de.skyengine.core.i18n.I18n;
import de.skyengine.game.world.save.WorldSaves;
import de.skyengine.graphics.gui.GuiManager;
import de.skyengine.graphics.gui.GuiScreen;
import de.skyengine.graphics.gui.layout.Anchor;
import de.skyengine.graphics.gui.layout.HStack;
import de.skyengine.graphics.gui.layout.VStack;
import de.skyengine.graphics.gui.widget.Button;
import de.skyengine.graphics.gui.widget.Label;
import de.skyengine.graphics.gui.widget.TextField;
import de.skyengine.graphics.gui.GuiText;

import java.util.Random;

/**
 * Neue Welt anlegen: Name + Seed (leer = zufällig; Zahl wird direkt übernommen, sonst
 * zählt der String-Hash). „Erstellen" legt das Savegame an und betritt die Welt sofort.
 */
public final class GuiCreateWorld extends GuiScreen {

    private TextField name;
    private TextField seed;

    public GuiCreateWorld(GuiScreen parent) {
        super(parent);
    }

    @Override
    public void init(GuiManager gui, float vW, float vH) {
        /* Eingaben über Resize/Scale-Wechsel retten (init baut die Widgets neu). */
        String prevName = this.name != null ? this.name.getText() : I18n.tr("world.create.default_name");
        String prevSeed = this.seed != null ? this.seed.getText() : "";
        this.components.clear();

        Label title = new Label(I18n.tr("world.create.title"), GuiText.TITLE).measure(gui);
        Label nameLabel = new Label(I18n.tr("world.create.name"), GuiText.NORMAL).measure(gui);
        this.name = new TextField(200, 20, 32, null).text(prevName);
        Label seedLabel = new Label(I18n.tr("world.create.seed"), GuiText.NORMAL).measure(gui);
        this.seed = new TextField(200, 20, 32, null).text(prevSeed);

        Button create = new Button(I18n.tr("world.create.create"), 98, 20, () -> {
            String worldName = this.name.getText().isBlank()
                    ? I18n.tr("world.create.default_name") : this.name.getText().trim();
            WorldSaves.WorldSave save = WorldSaves.create(worldName, parseSeed(this.seed.getText()));
            SkyEngine.get().getGame().enterWorld(save);
        });
        Button cancel = new Button(I18n.tr("gui.cancel"), 98, 20, () -> this.goBack(gui));

        /* MC-Layout: Titel weit oben, Inhalt im oberen Drittel angedockt. */
        VStack content = new VStack(4,
                nameLabel,
                this.name,
                seedLabel,
                this.seed,
                new HStack(4, create, cancel));
        this.components.add(title.anchor(Anchor.TOP_CENTER, 0, titleTop(vH)));
        this.components.add(content.anchor(Anchor.TOP_CENTER, 0, contentTop(vH, content.height())));
    }

    /** Leer -> Zufall; ganze Zahl -> direkt; sonst String-Hash (wie MC). */
    private static int parseSeed(String input) {
        String trimmed = input.trim();
        if (trimmed.isEmpty()) return new Random().nextInt();
        try {
            return Integer.parseInt(trimmed);
        } catch (NumberFormatException e) {
            return trimmed.hashCode();
        }
    }
}
