package de.skyengine.core.settings;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import de.skyengine.utils.logging.LogManager;
import de.skyengine.utils.logging.Logger;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
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
    private static final File FILE = new File("config/options.json");

    private static GameSettings instance;

    public enum GraphicsMode { FAST, FANCY }

    /**
     * Laub-Optik: LOW cullt Faces zwischen benachbarten Laub-Blöcken (dichte Kronen, deutlich
     * weniger Quads — MC-„Schnelle Grafik"-Look), MID = alle Laub-Faces (heutiger Look),
     * HIGH = Platzhalter für bushy leaves (überstehende Zusatz-Quads) — verhält sich bis zur
     * Umsetzung wie MID.
     */
    public enum LeavesQuality { LOW, MID, HIGH }

    /* GUI-Größe als Schieberegler 1..100 (feiner als MCs Stufen). 1 = 1.0x, 100 = 6.0x. */
    public int guiScale = 50;
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

    /** Pixel-Skalierungsfaktor der GUI aus {@link #guiScale} (1..100 -> 1.0..6.0). */
    public float guiScaleFactor() {
        int v = Math.clamp(this.guiScale, 1, 100);
        return 1.0f + (v - 1) / 99.0f * 5.0f;
    }

    /** Gebundener Key einer Aktion (Fallback: Default-Belegung). */
    public int key(String action) {
        Integer k = this.keyBindings != null ? this.keyBindings.get(action) : null;
        return k != null ? k : KeyBindings.defaults().getOrDefault(action, 0);
    }

    private void sanitize() {
        this.guiScale = Math.clamp(this.guiScale, 1, 100);
        this.renderDistance = Math.clamp(this.renderDistance, 2, 32);
        this.simulationDistance = Math.clamp(this.simulationDistance, 2, 32);
        this.fov = Math.clamp(this.fov, 30, 120);
        this.anisotropicFiltering = Math.clamp(this.anisotropicFiltering, 1, 16);
        this.msaaSamples = Math.clamp(this.msaaSamples, 0, 16);
        this.lodMaxDistance = Math.clamp(this.lodMaxDistance, 8, 512);
        this.vegetationDistance = Math.clamp(this.vegetationDistance, 0, 32);
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
