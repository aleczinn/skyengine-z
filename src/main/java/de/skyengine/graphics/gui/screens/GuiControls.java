package de.skyengine.graphics.gui.screens;

import de.skyengine.core.settings.GameSettings;
import de.skyengine.graphics.gui.GuiManager;
import de.skyengine.graphics.gui.GuiScreen;
import de.skyengine.graphics.gui.layout.HStack;
import de.skyengine.graphics.gui.layout.VStack;
import de.skyengine.graphics.gui.widget.Button;
import de.skyengine.graphics.gui.widget.CycleButton;
import de.skyengine.graphics.gui.widget.Slider;
import de.skyengine.graphics.gui.widget.Spacer;

import static de.skyengine.graphics.gui.screens.GuiOptionsMenu.CELL_H;
import static de.skyengine.graphics.gui.screens.GuiOptionsMenu.CELL_W;

/**
 * Steuerungs-Optionen: Maus-Sensitivität, Halten/Umschalten für Schleichen und Sprinten
 * (greift im {@code EntityPlayer}-Tick) und der Einstieg in die Tastenbelegung.
 */
public final class GuiControls extends GuiOptionsScreen {

    private final GameSettings settings = GameSettings.get();

    public GuiControls(GuiScreen parent) {
        super(parent);
    }

    @Override
    protected String title() {
        return "Steuerung";
    }

    @Override
    protected void buildContent(GuiManager gui, VStack content) {
        float wideW = CELL_W * 2 + 4;

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

        content.add(sensitivity);
        content.add(new HStack(4, sneak, sprint));
        content.add(new Spacer(0, 4));
        content.add(keybinds);
    }

    @Override
    public void onClose() {
        this.settings.save();
    }
}
