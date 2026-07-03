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
 * <p>Reine Daten + Persistenz — das Anwenden (Render-Distanz, FOV, VSync, GUI-Scale, Sensitivität)
 * erledigt der {@code GameContainer}, der Zugriff auf World/Camera/Window/Gui hat.
 */
public final class GameSettings {

    private static final Logger LOGGER = LogManager.getLogger(GameSettings.class.getName());
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final File FILE = new File("config/options.json");

    private static GameSettings instance;

    public enum GraphicsMode { FAST, FANCY }

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
    /* Anisotropes Filtern (1 = aus .. 16), wird beim Erzeugen des TextureArrays angewandt */
    public int anisotropicFiltering = 16;
    /* Heightmap-LOD jenseits der Render-Distanz (Fernsicht) */
    public boolean lodEnabled = true;
    public int lodDistance = 128;     // äußerer LOD-Ring in Chunks
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
        this.lodDistance = Math.clamp(this.lodDistance, 32, 256);
        if (this.mouseSensitivity <= 0) this.mouseSensitivity = 1.0;
        if (this.graphicsMode == null) this.graphicsMode = GraphicsMode.FANCY;
        if (this.keyBindings == null) {
            this.keyBindings = KeyBindings.defaults();
        } else {
            KeyBindings.defaults().forEach(this.keyBindings::putIfAbsent);       // fehlende Binds ergänzen
            this.keyBindings.keySet().retainAll(KeyBindings.defaults().keySet()); // verwaiste entfernen
        }
    }
}
