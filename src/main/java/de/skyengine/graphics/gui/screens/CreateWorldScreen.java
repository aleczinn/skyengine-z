package de.skyengine.graphics.gui.screens;

import de.skyengine.core.SkyEngine;
import de.skyengine.game.world.save.WorldSaves;
import de.skyengine.graphics.gui.GuiManager;
import de.skyengine.graphics.gui.Screen;
import de.skyengine.graphics.gui.layout.Anchor;
import de.skyengine.graphics.gui.layout.HStack;
import de.skyengine.graphics.gui.layout.VStack;
import de.skyengine.graphics.gui.widget.Button;
import de.skyengine.graphics.gui.widget.Label;
import de.skyengine.graphics.gui.widget.TextField;

import java.util.Random;

/**
 * Neue Welt anlegen: Name + Seed (leer = zufällig; Zahl wird direkt übernommen, sonst
 * zählt der String-Hash). „Erstellen" legt das Savegame an und betritt die Welt sofort.
 */
public final class CreateWorldScreen extends Screen {

    private TextField name;
    private TextField seed;

    public CreateWorldScreen(Screen parent) {
        super(parent);
    }

    @Override
    public void init(GuiManager gui, float vW, float vH) {
        /* Eingaben über Resize/Scale-Wechsel retten (init baut die Widgets neu). */
        String prevName = this.name != null ? this.name.getText() : "Neue Welt";
        String prevSeed = this.seed != null ? this.seed.getText() : "";
        this.components.clear();

        Label title = new Label("Neue Welt erstellen", 14).measure(gui);
        Label nameLabel = new Label("Name der Welt", 9).measure(gui);
        this.name = new TextField(200, 20, 32, null).text(prevName);
        Label seedLabel = new Label("Seed (leer = zufällig)", 9).measure(gui);
        this.seed = new TextField(200, 20, 32, null).text(prevSeed);

        Button create = new Button("Erstellen", 98, 20, () -> {
            String worldName = this.name.getText().isBlank() ? "Neue Welt" : this.name.getText().trim();
            WorldSaves.WorldSave save = WorldSaves.create(worldName, parseSeed(this.seed.getText()));
            SkyEngine.get().getGame().enterWorld(save);
        });
        Button cancel = new Button("Abbrechen", 98, 20, () -> this.goBack(gui));

        this.components.add(title);
        this.components.add(nameLabel);
        this.components.add(this.name);
        this.components.add(seedLabel);
        this.components.add(this.seed);
        this.components.add(create);
        this.components.add(cancel);

        VStack stack = new VStack(4)
                .add(title)
                .add(nameLabel)
                .add(this.name)
                .add(seedLabel)
                .add(this.seed)
                .add(new HStack(4).add(create).add(cancel));
        stack.layoutAnchored(vW, vH, Anchor.CENTER, 0, 0);
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
