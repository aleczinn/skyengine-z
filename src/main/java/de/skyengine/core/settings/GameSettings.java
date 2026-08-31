package de.skyengine.core.settings;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import de.skyengine.audio.SoundCategory;
import de.skyengine.utils.logging.LogManager;
import de.skyengine.utils.logging.Logger;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Persistente Spieleinstellungen (analog zu Minecrafts options.txt), als JSON unter
 * {@code ./config/options.json}. Wird beim Start geladen, beim Beenden und bei Änderungen gespeichert.
 * Singleton-Zugriff über {@link #get()}.
 *
 * <p>Reine Daten + Persistenz — das Anwenden (Render-Distanz, FOV, VSync, GUI-Scale, Sensitivität,
 * AO, MSAA, Fog und anisotropes Filtern) erledigen {@code GameContainer}
 * bzw. die jeweiligen Renderer-/Framebuffer-Stellen, die Zugriff auf Dimension/Camera/Window/Gui haben.
 */
public final class GameSettings {

    private static final Logger LOGGER = LogManager.getLogger(GameSettings.class.getName());
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    /* Liegt im zentralen Spiel-Root (standardmaessig %APPDATA%/.voxelstories). */
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

    /** Menge kosmetischer Weltpartikel. Die Caps verhindern ungebremste Burst-Kosten. */
    public enum ParticleQuality {
        MINIMAL(2048, 0.25F, 0F, 16F, 32F),
        DECREASED(4096, 0.5F, 0.5F, 24F, 48F),
        ALL(8192, 1F, 1F, 32F, 64F);

        public final int capacity;
        public final float eventRate;
        public final float ambientRate;
        public final float ambientDistance;
        public final float eventDistance;

        ParticleQuality(int capacity, float eventRate, float ambientRate,
                        float ambientDistance, float eventDistance) {
            this.capacity = capacity;
            this.eventRate = eventRate;
            this.ambientRate = ambientRate;
            this.ambientDistance = ambientDistance;
            this.eventDistance = eventDistance;
        }
    }

    /* GUI-Größe als GANZZAHLIGER Faktor: 0 = automatisch (größter Faktor, der ins Fenster
       passt), sonst 1..MAX_GUI_SCALE. Zwischenstufen gibt es nicht und können es nicht geben:
       GUI-Grafik ist ein Texel-Raster mit GL_NEAREST, eine 1 Texel breite Linie muss also auf
       eine ganze Zahl Gerätepixel fallen (bei Faktor 3.85 wurde sie mal 3, mal 4 px breit).
       Der Feldname darf NICHT 'guiScale' sein: alte options.json tragen noch ein gleichnamiges
       Feld mit ganz anderer Bedeutung (1..100 -> 1.0..6.0), das GSON sonst einlesen würde.
       Ersetzt guiScalePercent; alte Dateien landen über den Default bei automatisch. */
    public int guiScaleLevel = 0;
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
    public ParticleQuality particleQuality = ParticleQuality.ALL;
    /* Anisotropes Filtern (1 = aus, 2, 4, 8, 16), wird beim Erzeugen des TextureArrays angewandt */
    public int anisotropicFiltering = 16;
    /* MSAA-Sample-Zahl des Offscreen-Framebuffers (0 = aus, 2, 4, 8, 16), greift beim nächsten
       Framebuffer-Aufbau (Start bzw. Fenster-Resize). 4 gegen das kriechende Kanten-Aliasing
       des fernen Voxel-Terrains. */
    public int msaaSamples = 4;
    /* Distanz-Fog Richtung Clear-Color am Sichtweiten-Rand (dämpft Horizont-Flimmern
       und versteckt die Far-Plane-Kante). */
    public boolean fog = true;
    /* Helligkeit in Prozent (5er-Raster): 0 = AUS = Fullbright — das Himmelslicht bleibt
       berechnet, wird im Shader aber wirkungslos, das Bild ist bit-identisch zum Zustand ohne
       Lichtsystem. 5..100 = Minecraft-Helligkeitsregler: hebt Licht 0 auf brightness × 9 %
       an (Remap über u_MinLight im Chunk-Shader). Wirkt live über ein Uniform — KEIN Remesh,
       weil das Licht als eigener Kanal im Vertex liegt und nicht in die Farbe multipliziert ist. */
    public int brightness = 50;
    /* View-Bobbing (Kamera wippt beim Laufen, wie MC) — aus für Motion-Sickness-Empfindliche. */
    public boolean viewBobbing = true;
    /* Kamera-Roll beim Schaden (Hurt-Tilt). */
    public boolean damageTilt = true;
    /* Kleinvegetation (Gras/Blumen/Pilze): Distanz in Chunks, ab der die Ausdünnung beginnt
       (graduell per Pflanzen-Hash, komplett weg bei +50 %). 0 = keine Ausdünnung. */
    public int vegetationDistance = 8;
    /* Gesamtlautstärke 0..100 (wirkt global als OpenAL-Listener-Gain). */
    public int masterVolume = 100;
    /* Kanal-Lautstärken 0..100, Keys = SoundCategory-Namen (ersetzt das alte musicVolume-Feld:
       Musik ist jetzt der MUSIC-Kanal; alte options.json fällt auf die Kanal-Defaults zurück). */
    public Map<String, Integer> soundVolumes = defaultSoundVolumes();
    /* OpenAL-Ausgabegerät (voller ALC-Name, "" = Systemstandard). */
    public String audioDevice = "";
    /* Pausenmenü: true = Musik pausiert mit (wie die Geräusche), false = nur Geräusche pausieren. */
    public boolean pauseMusicInMenus = true;
    /* Schleichen/Sprinten: false = Taste halten, true = Umschalten (Toggle). */
    public boolean sneakToggle = false;
    public boolean sprintToggle = false;
    /* Sprache (Datei-Code unter game/lang/, z. B. de_de / en_us). */
    public String language = "de_de";
    /** Aktive Ressourcenpakete, hoechste Prioritaet zuerst. */
    public List<String> resourcePacks = new ArrayList<>();
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

    /** Obergrenze für {@link #guiScaleLevel}; der tatsächliche Faktor wird zusätzlich von der
        Fenstergröße gedeckelt (siehe {@code GuiManager.resolveScale}). */
    public static final int MAX_GUI_SCALE = 6;

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
        /* 0 = automatisch (alte options.json ohne das Feld landen über den GSON-Default dort) */
        this.guiScaleLevel = Math.clamp(this.guiScaleLevel, 0, MAX_GUI_SCALE);
        this.renderDistance = Math.clamp(this.renderDistance, 2, 32);
        this.simulationDistance = Math.clamp(this.simulationDistance, 2, 32);
        this.fov = Math.clamp(this.fov, 30, 120);
        this.anisotropicFiltering = Math.clamp(this.anisotropicFiltering, 1, 16);
        this.msaaSamples = Math.clamp(this.msaaSamples, 0, 16);
        this.vegetationDistance = Math.clamp(this.vegetationDistance, 0, 32);
        this.brightness = Math.clamp((this.brightness + 2) / 5 * 5, 0, 100);
        this.masterVolume = Math.clamp(this.masterVolume, 0, 100);
        if (this.soundVolumes == null) {
            this.soundVolumes = defaultSoundVolumes();
        } else {
            for (SoundCategory category : SoundCategory.values()) {
                Integer v = this.soundVolumes.get(category.name());
                this.soundVolumes.put(category.name(), Math.clamp(v != null ? v : category.defaultVolume, 0, 100));
            }
            /* Verwaiste Keys (umbenannte/entfernte Kanäle) rauswerfen — Muster keyBindings. */
            this.soundVolumes.keySet().retainAll(defaultSoundVolumes().keySet());
        }
        if (this.audioDevice == null) this.audioDevice = "";
        if (this.language == null || this.language.isBlank()) this.language = "de_de";
        if (this.resourcePacks == null) {
            this.resourcePacks = new ArrayList<>();
        } else {
            /* Reihenfolge erhalten, aber leere und doppelte Eintraege entfernen. */
            java.util.LinkedHashSet<String> unique = new java.util.LinkedHashSet<>();
            for (String pack : this.resourcePacks) {
                if (pack != null && !pack.isBlank()) unique.add(pack);
            }
            this.resourcePacks = new ArrayList<>(unique);
        }
        if (this.mouseSensitivity <= 0) this.mouseSensitivity = 1.0;
        if (this.graphicsMode == null) this.graphicsMode = GraphicsMode.FANCY;
        if (this.leavesQuality == null) this.leavesQuality = LeavesQuality.MID;
        if (this.particleQuality == null) this.particleQuality = ParticleQuality.ALL;
        if (this.keyBindings == null) {
            this.keyBindings = KeyBindings.defaults();
        } else {
            KeyBindings.defaults().forEach(this.keyBindings::putIfAbsent);       // fehlende Binds ergänzen
            this.keyBindings.keySet().retainAll(KeyBindings.defaults().keySet()); // verwaiste entfernen
        }
    }
}
