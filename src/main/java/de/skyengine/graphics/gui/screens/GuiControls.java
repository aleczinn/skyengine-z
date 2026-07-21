package de.skyengine.graphics.gui.screens;

import de.skyengine.core.settings.GameSettings;
import de.skyengine.graphics.gui.GuiManager;
import de.skyengine.graphics.gui.GuiScreen;
import de.skyengine.graphics.gui.layout.Anchor;
import de.skyengine.graphics.gui.layout.HStack;
import de.skyengine.graphics.gui.layout.VStack;
import de.skyengine.graphics.gui.widget.Button;
import de.skyengine.graphics.gui.widget.CycleButton;
import de.skyengine.graphics.gui.widget.Label;
import de.skyengine.graphics.gui.widget.Slider;
import de.skyengine.graphics.gui.widget.Spacer;

import static de.skyengine.graphics.gui.screens.GuiOptionsMenu.CELL_H;
import static de.skyengine.graphics.gui.screens.GuiOptionsMenu.CELL_W;

/**
 * Steuerungs-Optionen: Maus-Sensitivität, Halten/Umschalten für Schleichen und Sprinten
 * (greift im {@code EntityPlayer}-Tick) und der Einstieg in die Tastenbelegung.
 */
public final class GuiControls extends GuiScreen {

    private final GameSettings settings = GameSettings.get();

    public GuiControls(GuiScreen parent) {
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
        float wideW = CELL_W * 2 + 4;

        Label title = new Label("Steuerung", 14).measure(gui);

        Slider sensitivity = new Slider(wideW, CELL_H, 10, 300, 5, this.settings.mouseSensitivity * 100,
                v -> "Sensitivität: " + (int) v + " %",
                v -> this.settings.mouseSensitivity = v / 100.0, null);

        CycleButton<Boolean> sneak = new CycleButton<>("Schleichen", CELL_W, CELL_H,
                new Boolean[]{false, true}, this.settings.sneakToggle,
                v -> v ? "Umschalten" : "Halten",
                v -> this.settings.sneakToggle = v);

        CycleButton<Boolean> sprint = new CycleButton<>("Sprinten", CELL_W, CELL_H,
                new Boolean[]{false, true}, this.settings.sprintToggle,
                v -> v ? "Umschalten" : "Halten",
                v -> this.settings.sprintToggle = v);

        Button keybinds = new Button("Tastenbelegung...", wideW, CELL_H, () -> gui.open(new GuiKeybinds(this)));
        Button done = new Button("Fertig", () -> this.goBack(gui));

        VStack content = new VStack(4,
                sensitivity,
                new HStack(4, sneak, sprint),
                new Spacer(0, 4),
                keybinds,
                new Spacer(0, 8),
                done);
        this.components.add(title.anchor(Anchor.TOP_CENTER, 0, titleTop(vH)));
        this.components.add(content.anchor(Anchor.TOP_CENTER, 0, contentTop(vH, content.height())));
    }

    @Override
    public void onClose() {
        this.settings.save();
    }
}
