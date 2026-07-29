package de.skyengine.graphics.gui.screens;

import de.skyengine.audio.SoundCategory;
import de.skyengine.core.SkyEngine;
import de.skyengine.core.i18n.I18n;
import de.skyengine.core.settings.GameSettings;
import de.skyengine.game.GameContainer;
import de.skyengine.graphics.gui.GuiManager;
import de.skyengine.graphics.gui.GuiScreen;
import de.skyengine.graphics.gui.layout.HStack;
import de.skyengine.graphics.gui.layout.VStack;
import de.skyengine.graphics.gui.widget.CycleButton;
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
public final class GuiSoundOptions extends GuiOptionsScreen {

    /** Präfix, das OpenAL-Soft jedem Gerätenamen voranstellt — für die Anzeige unnötig. */
    private static final String DEVICE_PREFIX = "OpenAL Soft on ";

    private final GameSettings settings = GameSettings.get();

    public GuiSoundOptions(GuiScreen parent) {
        super(parent);
    }

    @Override
    protected String title() {
        return I18n.tr("options.sound.title");
    }

    @Override
    protected void buildContent(GuiManager gui, VStack content) {
        GameContainer game = SkyEngine.get().getGame();
        float wideW = CELL_W * 2 + 4;

        Slider master = new Slider(wideW, CELL_H, 0, 100, 1, this.settings.masterVolume,
                v -> I18n.tr("options.sound.master", (int) v),
                v -> {
                    this.settings.masterVolume = (int) v;
                    game.applyAudioSettings();
                }, null);

        /* Kanal-Slider in Enum-Reihenfolge, zweispaltig gepaart. */
        List<Slider> channels = new ArrayList<>();
        for (SoundCategory category : SoundCategory.values()) {
            channels.add(new Slider(CELL_W, CELL_H, 0, 100, 1, this.settings.soundVolume(category),
                    v -> I18n.tr(category.translationKey()) + ": " + (int) v + " %",
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
        CycleButton<String> device = new CycleButton<>(I18n.tr("options.sound.device"), wideW, CELL_H,
                devices.toArray(new String[0]), current,
                GuiSoundOptions::deviceLabel,
                v -> {
                    this.settings.audioDevice = v;
                    gui.sound().setDevice(v);
                });

        content.add(master);
        content.add(new HStack(4, channels.get(0), channels.get(1)));
        content.add(new HStack(4, channels.get(2), channels.get(3)));
        content.add(new HStack(4, channels.get(4), channels.get(5)));
        content.add(new HStack(4, channels.get(6), channels.get(7)));
        content.add(device);
    }

    /** Anzeigename: Systemstandard-Eintrag, OpenAL-Soft-Präfix weg, Überlänge kappen. */
    private static String deviceLabel(String name) {
        if (name.isEmpty()) return I18n.tr("options.sound.device_default");
        String label = name.startsWith(DEVICE_PREFIX) ? name.substring(DEVICE_PREFIX.length()) : name;
        return label.length() > 34 ? label.substring(0, 31) + "..." : label;
    }

    @Override
    public void onClose() {
        this.settings.save();
    }
}
