package de.skyengine.graphics.gui.screens;

import de.skyengine.audio.SoundCategory;
import de.skyengine.core.SkyEngine;
import de.skyengine.core.settings.GameSettings;
import de.skyengine.game.GameContainer;
import de.skyengine.graphics.gui.GuiManager;
import de.skyengine.graphics.gui.GuiScreen;
import de.skyengine.graphics.gui.layout.Anchor;
import de.skyengine.graphics.gui.layout.HStack;
import de.skyengine.graphics.gui.layout.VStack;
import de.skyengine.graphics.gui.widget.Button;
import de.skyengine.graphics.gui.widget.CycleButton;
import de.skyengine.graphics.gui.widget.Label;
import de.skyengine.graphics.gui.widget.Slider;

import java.util.ArrayList;
import java.util.List;

import static de.skyengine.graphics.gui.screens.GuiOptionsMenu.CELL_H;
import static de.skyengine.graphics.gui.screens.GuiOptionsMenu.CELL_W;

/**
 * Musik- & Geräuschoptionen: breiter Gesamtlautstärke-Slider, darunter die acht
 * {@link SoundCategory}-Kanäle zweispaltig, darunter die Wahl des Ausgabegeräts
 * (ALC_SOFT_reopen_device — greift sofort, ohne Neuladen der Sounds).
 */
public final class GuiSoundOptions extends GuiScreen {

    /** Präfix, das OpenAL-Soft jedem Gerätenamen voranstellt — für die Anzeige unnötig. */
    private static final String DEVICE_PREFIX = "OpenAL Soft on ";

    private final GameSettings settings = GameSettings.get();

    public GuiSoundOptions(GuiScreen parent) {
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
        GameContainer game = SkyEngine.get().getGame();
        float wideW = CELL_W * 2 + 4;

        Label title = new Label("Musik & Geräusche", 14).measure(gui);

        Slider master = new Slider(wideW, CELL_H, 0, 100, 5, this.settings.masterVolume,
                v -> "Gesamtlautstärke: " + (int) v + " %",
                v -> {
                    this.settings.masterVolume = (int) v;
                    game.applyAudioSettings();
                }, null);

        /* Kanal-Slider in Enum-Reihenfolge, zweispaltig gepaart. */
        List<Slider> channels = new ArrayList<>();
        for (SoundCategory category : SoundCategory.values()) {
            channels.add(new Slider(CELL_W, CELL_H, 0, 100, 5, this.settings.soundVolume(category),
                    v -> category.label + ": " + (int) v + " %",
                    v -> {
                        this.settings.soundVolumes.put(category.name(), (int) v);
                        game.applyAudioSettings();
                    }, null));
        }

        /* Gerätewahl: "" = Systemstandard; gespeichertes Gerät kann abgesteckt sein -> Fallback. */
        List<String> devices = new ArrayList<>();
        devices.add("");
        devices.addAll(gui.sound().listDevices());
        String current = devices.contains(this.settings.audioDevice) ? this.settings.audioDevice : "";
        CycleButton<String> device = new CycleButton<>("Gerät", wideW, CELL_H,
                devices.toArray(new String[0]), current,
                GuiSoundOptions::deviceLabel,
                v -> {
                    this.settings.audioDevice = v;
                    gui.sound().setDevice(v);
                });

        Button done = new Button("Fertig", () -> this.goBack(gui));

        /* Keine Spacer: der Screen ist der höchste — mit Luft liefe "Fertig" bei der
           Mindest-vHöhe 210 (720p) unten aus dem Bild. */
        VStack content = new VStack(4,
                master,
                new HStack(4, channels.get(0), channels.get(1)),
                new HStack(4, channels.get(2), channels.get(3)),
                new HStack(4, channels.get(4), channels.get(5)),
                new HStack(4, channels.get(6), channels.get(7)),
                device,
                done);
        this.components.add(title.anchor(Anchor.TOP_CENTER, 0, titleTop(vH)));
        this.components.add(content.anchor(Anchor.TOP_CENTER, 0, contentTop(vH, content.height())));
    }

    /** Anzeigename: Systemstandard-Eintrag, OpenAL-Soft-Präfix weg, Überlänge kappen. */
    private static String deviceLabel(String name) {
        if (name.isEmpty()) return "Systemstandard";
        String label = name.startsWith(DEVICE_PREFIX) ? name.substring(DEVICE_PREFIX.length()) : name;
        return label.length() > 34 ? label.substring(0, 31) + "..." : label;
    }

    @Override
    public void onClose() {
        this.settings.save();
    }
}
