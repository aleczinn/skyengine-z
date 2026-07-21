package de.skyengine.core.settings;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import de.skyengine.audio.SoundCategory;
import de.skyengine.utils.logging.LogManager;
import de.skyengine.utils.logging.Logger;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Persistente Spieleinstellungen (analog zu Minecrafts options.txt), als JSON unter
 * {@code ./config/options.json}. Wird beim Start geladen, beim Beenden und bei Änderungen gespeichert.
 * Singleton-Zugriff über {@link #get()}.
 *
 * <p>Reine Daten + Persistenz — das Anwenden (Render-Distanz, FOV, VSync, GUI-Scale, Sensitivität,
 * AO, MSAA, Fog, LOD an/aus + Reichweite, anisotropes Filtern, …) erledigen {@code GameContainer}
 * bzw. die jeweiligen Renderer-/Framebuffer-Stellen, die Zugriff auf World/Camera/Window/Gui haben.
 */
public final class GameSettings {

    private static final Logger LOGGER = LogManager.getLogger(GameSettings.class.getName());
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    /* Liegt im Spiel-Root (%APPDATA%\.skyengine), nicht im Arbeitsverzeichnis. */
    private static final File FILE = de.skyengine.core.file.GameDirectory.resolve("config/options.json");

    private static GameSettings instance;

    public enum GraphicsMode { FAST, FANCY }

    /**
     * Laub-Optik: LOW cullt Faces zwischen benachbarten Laub-Blöcken (dichte Kronen, deutlich
     * weniger Quads — MC-„Schnelle Grafik"-Look), MID = alle Laub-Faces (heutiger Look),
     * HIGH = Platzhalter für bushy leaves (überstehende Zusatz-Quads) — verhält sich bis zur
     * Umsetzung wie MID.
     */
    public enum LeavesQuality { LOW, MID, HIGH }

    /* GUI-Größe in Prozent (30..170, 5er-Schritte): 100 % = Referenz-Look (Faktor 3.5). Ersetzt
       das alte guiScale-Feld (1..100 -> 1.0..6.0); alte options.json fallen auf 100 % zurück. */
    public int guiScalePercent = 100;
    public int renderDistance = 16;   // in Chunks
    public int simulationDistance = 10; // in Chunks; nur Chunks in diesem Radius ticken (wie MC)
    public int fov = 75;
    public boolean vsync = false;
    public double mouseSensitivity = 1.0;
    public GraphicsMode graphicsMode = GraphicsMode.FANCY;
    /* volatile: wird von den Mesher-Worker-Threads gelesen (Toggle löst Voll-Remesh aus) */
    public volatile boolean ambientOcclusion = true;
    /* volatile: wird von den Mesher-Worker-Threads gelesen (Zyklus-Hotkey löst Voll-Remesh aus) */
    public volatile LeavesQuality leavesQuality = LeavesQuality.MID;
    /* Anisotropes Filtern (1 = aus, 2, 4, 8, 16), wird beim Erzeugen des TextureArrays angewandt */
    public int anisotropicFiltering = 16;
    /* MSAA-Sample-Zahl des Offscreen-Framebuffers (0 = aus, 2, 4, 8, 16), greift beim nächsten
       Framebuffer-Aufbau (Start bzw. Fenster-Resize). 4 gegen das kriechende Kanten-Aliasing
       des fernen Voxel-Terrains. */
    public int msaaSamples = 4;
    /* Distanz-Fog Richtung Clear-Color am Sichtweiten-Rand (dämpft Horizont-Flimmern,
       versteckt Far-Plane-Kante und LOD-Übergänge) */
    public boolean fog = true;
    /* Kleinvegetation (Gras/Blumen/Pilze): Distanz in Chunks, ab der die Ausdünnung beginnt
       (graduell per Pflanzen-Hash, komplett weg bei +50 %). 0 = keine Ausdünnung. */
    public int vegetationDistance = 8;
    /* Heightmap-LOD jenseits der Render-Distanz (Fernsicht) */
    public boolean lodEnabled = true;
    /* Äußerste LOD-Reichweite in Chunks. Level ergeben sich automatisch: Level L endet bei
       renderDistance·2^L, gedeckelt bei lodMaxDistance (rd16/lod128 → L1 16-32, L2 32-64,
       L3 64-128). lodMaxDistance <= renderDistance schaltet das LOD faktisch ab. */
    public int lodMaxDistance = 128;
    /* Gesamtlautstärke 0..100 (wirkt global als OpenAL-Listener-Gain). */
    public int masterVolume = 100;
    /* Kanal-Lautstärken 0..100, Keys = SoundCategory-Namen (ersetzt das alte musicVolume-Feld:
       Musik ist jetzt der MUSIC-Kanal; alte options.json fällt auf die Kanal-Defaults zurück). */
    public Map<String, Integer> soundVolumes = defaultSoundVolumes();
    /* OpenAL-Ausgabegerät (voller ALC-Name, "" = Systemstandard). */
    public String audioDevice = "";
    /* Schleichen/Sprinten: false = Taste halten, true = Umschalten (Toggle). */
    public boolean sneakToggle = false;
    public boolean sprintToggle = false;
    public Map<String, Integer> keyBindings = KeyBindings.defaults();

    public static GameSettings get() {
        if (instance == null) instance = load();
        return instance;
    }

    public static GameSettings load() {
        if (FILE.exists()) {
            try (FileReader r = new FileReader(FILE)) {
                GameSettings s = GSON.fromJson(r, GameSettings.class);
                if (s != null) {
                    s.sanitize();
                    LOGGER.info("Einstellungen geladen: " + FILE.getPath());
                    return s;
                }
            } catch (Exception e) {
                LOGGER.error("Einstellungen fehlerhaft, nutze Defaults", e);
            }
        }
        GameSettings s = new GameSettings();
        s.save();
        return s;
    }

    public void save() {
        try {
            File dir = FILE.getParentFile();
            if (dir != null && !dir.exists()) dir.mkdirs();
            try (FileWriter w = new FileWriter(FILE)) {
                GSON.toJson(this, w);
            }
        } catch (Exception e) {
            LOGGER.error("Einstellungen konnten nicht gespeichert werden", e);
        }
    }

    /** Pixel-Skalierungsfaktor der GUI aus {@link #guiScalePercent} (100 % = Faktor 3.5). */
    public float guiScaleFactor() {
        return 3.5f * Math.clamp(this.guiScalePercent, 30, 170) / 100.0f;
    }

    /** Gebundener Key einer Aktion (Fallback: Default-Belegung). */
    public int key(String action) {
        Integer k = this.keyBindings != null ? this.keyBindings.get(action) : null;
        return k != null ? k : KeyBindings.defaults().getOrDefault(action, 0);
    }

    /** Lautstärke eines Sound-Kanals 0..100 (Fallback: Kanal-Default). */
    public int soundVolume(SoundCategory category) {
        Integer v = this.soundVolumes != null ? this.soundVolumes.get(category.name()) : null;
        return v != null ? v : category.defaultVolume;
    }

    private static Map<String, Integer> defaultSoundVolumes() {
        Map<String, Integer> map = new LinkedHashMap<>();
        for (SoundCategory category : SoundCategory.values()) {
            map.put(category.name(), category.defaultVolume);
        }
        return map;
    }

    private void sanitize() {
        /* 5er-Raster + Grenzen (alte options.json ohne das Feld landet über den GSON-Default bei 100) */
        this.guiScalePercent = Math.clamp((this.guiScalePercent + 2) / 5 * 5, 30, 170);
        this.renderDistance = Math.clamp(this.renderDistance, 2, 32);
        this.simulationDistance = Math.clamp(this.simulationDistance, 2, 32);
        this.fov = Math.clamp(this.fov, 30, 120);
        this.anisotropicFiltering = Math.clamp(this.anisotropicFiltering, 1, 16);
        this.msaaSamples = Math.clamp(this.msaaSamples, 0, 16);
        this.lodMaxDistance = Math.clamp(this.lodMaxDistance, 8, 512);
        this.vegetationDistance = Math.clamp(this.vegetationDistance, 0, 32);
        this.masterVolume = Math.clamp(this.masterVolume, 0, 100);
        if (this.soundVolumes == null) {
            this.soundVolumes = defaultSoundVolumes();
        } else {
            for (SoundCategory category : SoundCategory.values()) {
                Integer v = this.soundVolumes.get(category.name());
                this.soundVolumes.put(category.name(),
                        Math.clamp(v != null ? v : category.defaultVolume, 0, 100));
            }
            /* Verwaiste Keys (umbenannte/entfernte Kanäle) rauswerfen — Muster keyBindings. */
            this.soundVolumes.keySet().retainAll(defaultSoundVolumes().keySet());
        }
        if (this.audioDevice == null) this.audioDevice = "";
        if (this.mouseSensitivity <= 0) this.mouseSensitivity = 1.0;
        if (this.graphicsMode == null) this.graphicsMode = GraphicsMode.FANCY;
        if (this.leavesQuality == null) this.leavesQuality = LeavesQuality.MID;
        if (this.keyBindings == null) {
            this.keyBindings = KeyBindings.defaults();
        } else {
            KeyBindings.defaults().forEach(this.keyBindings::putIfAbsent);       // fehlende Binds ergänzen
            this.keyBindings.keySet().retainAll(KeyBindings.defaults().keySet()); // verwaiste entfernen
        }
    }
}
