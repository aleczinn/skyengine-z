package de.skyengine.graphics.gui.screens;

import de.skyengine.core.i18n.I18n;
import de.skyengine.core.settings.GameSettings;
import de.skyengine.graphics.gui.GuiManager;
import de.skyengine.graphics.gui.GuiScreen;
import de.skyengine.graphics.gui.layout.HStack;
import de.skyengine.graphics.gui.layout.VStack;
import de.skyengine.graphics.gui.widget.Button;
import de.skyengine.graphics.gui.widget.CycleButton;
import de.skyengine.graphics.gui.widget.Slider;
import de.skyengine.graphics.gui.widget.Spacer;

import java.util.Locale;

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
        return I18n.tr("options.controls.title");
    }

    @Override
    protected void buildContent(GuiManager gui, VStack content) {
        float wideW = CELL_W * 2 + 4;

        Slider sensitivity = new Slider(wideW, CELL_H, 10, 300, 5, this.settings.mouseSensitivity * 100,
                v -> I18n.tr("options.controls.sensitivity", (int) v),
                v -> this.settings.mouseSensitivity = v / 100.0, null);

        Slider zoomFactor = new Slider(wideW, CELL_H,
                GameSettings.ZOOM_FACTOR_MIN, GameSettings.ZOOM_FACTOR_MAX, 0.5,
                this.settings.zoomFactor,
                v -> I18n.tr("options.controls.zoom_factor", formatZoomFactor(v)),
                v -> this.settings.zoomFactor = (float) v, null);

        CycleButton<Boolean> sneak = new CycleButton<>(I18n.tr("options.controls.sneak"), CELL_W, CELL_H,
                new Boolean[]{false, true}, this.settings.sneakToggle,
                v -> I18n.tr(v ? "options.controls.toggle" : "options.controls.hold"),
                v -> this.settings.sneakToggle = v);

        CycleButton<Boolean> sprint = new CycleButton<>(I18n.tr("options.controls.sprint"), CELL_W, CELL_H,
                new Boolean[]{false, true}, this.settings.sprintToggle,
                v -> I18n.tr(v ? "options.controls.toggle" : "options.controls.hold"),
                v -> this.settings.sprintToggle = v);

        Button keybinds = new Button(I18n.tr("options.controls.keybinds"), wideW, CELL_H, () -> gui.open(new GuiKeybinds(this)));

        content.add(sensitivity);
        content.add(zoomFactor);
        content.add(new HStack(4, sneak, sprint));
        content.add(new Spacer(0, 4));
        content.add(keybinds);
    }

    @Override
    public void onClose() {
        this.settings.save();
    }

    private static String formatZoomFactor(double value) {
        return value == Math.rint(value)
                ? String.format(Locale.ROOT, "%.0f", value)
                : String.format(Locale.ROOT, "%.1f", value);
    }
}
