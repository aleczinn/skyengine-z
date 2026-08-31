package de.skyengine.graphics.post;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import de.skyengine.utils.logging.LogManager;
import de.skyengine.utils.logging.Logger;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;

/**
 * Parameterblock der Post-Processing-Kette. <b>Runtime-Quelle ist dieses Objekt</b>, nicht
 * die JSON: Engine-Systeme (später Biome/Dimensionen/Unterwasser/Schaden/Debug-Menü) ändern
 * Werte über die Setter — das Dirty-Flag sorgt dafür, dass der {@link PostProcessor} das
 * UBO nur bei Änderung neu hochlädt. Shader werden dafür NIE angefasst.
 *
 * <p>Die JSON ({@code config/postprocessing.json}) speichert die dauerhaft gewählten Werte
 * des Post-Processing-Menüs und bleibt zugleich über den Reload-Hotkey zur Laufzeit editierbar.
 * Fehlt sie, wird sie einmalig mit Neutral-Defaults angelegt.
 *
 * <p>Alle Defaults sind neutral: die Kette ist dann ein reiner Copy (Look unverändert).
 */
public final class PostProcessingSettings {

    public static final float EXPOSURE_MIN = 0.25F, EXPOSURE_MAX = 4.0F;
    public static final float GAMMA_MIN = 0.5F, GAMMA_MAX = 2.5F;
    public static final float MULTIPLIER_MIN = 0.0F, MULTIPLIER_MAX = 2.0F;
    public static final float OFFSET_MIN = -0.5F, OFFSET_MAX = 0.5F;
    public static final float COLOR_SHIFT_MIN = -1.0F, COLOR_SHIFT_MAX = 1.0F;
    public static final float TAA_HISTORY_MIN = 0.0F, TAA_HISTORY_MAX = 0.98F;
    public static final float TAA_MIP_BIAS_MIN = -2.0F, TAA_MIP_BIAS_MAX = 0.0F;
    public static final float SHARPEN_MIN = 0.0F, SHARPEN_MAX = 1.0F;

    private static final Logger LOGGER = LogManager.getLogger(PostProcessingSettings.class.getName());
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    /* Liegt im Spiel-Root (%APPDATA%\.skyengine), nicht im Arbeitsverzeichnis. */
    private static final File FILE = de.skyengine.core.file.GameDirectory.resolve("config/postprocessing.json");

    /** Tonemap-Operator (Domänenwechsel HDR → display-referred). NONE = Passthrough. */
    public enum TonemapOperator { NONE, REINHARD, ACES }

    /**
     * Anti-Aliasing — EIN Schalter für die komplette Kette (F9 zyklt):
     * <ul>
     *   <li>{@code NONE} — roh, schärfstes Bild, Kanten flimmern.</li>
     *   <li>{@code FXAA} — billiges Kanten-AA (ein Fullscreen-Pass).</li>
     *   <li>{@code TAA} — zeitliche Akkumulation, scharf + stabil.</li>
     *   <li>{@code TAA_FXAA} — BSL-Kette (FXAA-Vorstufe vor TAA): ruhigere Kanten,
     *       ~+40 % AA-Kosten gegenüber TAA.</li>
     *   <li>{@code MSAA} — Hardware-Multisampling; Sample-Zahl aus GameSettings.msaaSamples
     *       (0 → 4). Teuerster Modus bei hoher Auflösung; der Szene-Framebuffer folgt dem
     *       Modus (nur Nicht-MSAA-Modi haben eine sample-bare Depth-Textur).</li>
     * </ul>
     * SMAA wäre ein späterer Modus im selben Switch.
     */
    public enum AntiAliasingMode { NONE, FXAA, TAA, TAA_FXAA, MSAA }

    /** Debug-Visualisierung der Post-Kette — reiner Hook, in Phase 1 ohne Wirkung.
        Spätere Werte z.B. SCENE_DEPTH, GRADING_DELTA, AA_DIFF. */
    public enum PostDebugMode { NONE }

    /* --- szenen-linear (vor Tonemap) --- */
    private float exposure = 1.0F;
    private float temperature = 0.0F;  // Weißabgleich: + warm (rot) / - kalt (blau)
    private float tint = 0.0F;         // Weißabgleich: + grün / - magenta
    private TonemapOperator tonemapOperator = TonemapOperator.NONE;

    /* --- display-referred (nach Tonemap) --- */
    private float lift = 0.0F;         // hebt Schwarzwerte (additiv)
    private float gain = 1.0F;         // skaliert Weißwerte (multiplikativ)
    private float shadows = 1.0F;      // Luminanz-gewichtete Multiplikatoren
    private float midtones = 1.0F;
    private float highlights = 1.0F;
    private float contrast = 1.0F;     // Pivot 0.5
    private float brightness = 0.0F;   // additiv
    private float saturation = 1.0F;   // 0 = Graustufen, 1 = neutral
    private float vibrance = 0.0F;     // wirkt v.a. auf schwach gesättigte Farben (≠ Saturation)
    private float gamma = 1.0F;        // Output Transform, zuletzt

    /* --- Pipeline-Modi --- */
    private AntiAliasingMode aaMode = AntiAliasingMode.NONE;
    private PostDebugMode debugMode = PostDebugMode.NONE;

    /* TAA: History-Gewicht (höher = ruhiger/weicher, niedriger = schärfer/flimmriger);
       bei Kamerabewegung senkt der Resolve-Shader es zusätzlich adaptiv ab. */
    private float taaHistoryWeight = 0.85F;
    /* TAA: LOD-Bias des Block-TextureArrays solange TAA aktiv ist (negativ = schärfer;
       holt von der zeitlichen Mittelung weggeglättetes Mip-Detail zurück). */
    private float taaMipBias = -0.5F;

    /* --- Reserviert: Bloom/Vignette werden noch von keinem Pass gelesen. Sharpen wird
           dagegen vom temporalen AA-Pfad als Nachschärfung ausgewertet. --- */
    private float bloomIntensity = 0.0F;
    private float bloomThreshold = 1.0F;
    private float vignette = 0.0F;
    private float sharpen = 0.0F;

    /* Dirty-Flag: true = UBO muss neu hochgeladen werden (GSON umgeht Setter -> nach dem
       Laden explizit gesetzt). transient hält es aus der JSON raus. */
    private transient boolean dirty = true;

    public static PostProcessingSettings load() {
        if (FILE.exists()) {
            try (FileReader r = new FileReader(FILE)) {
                PostProcessingSettings s = GSON.fromJson(r, PostProcessingSettings.class);
                if (s != null) {
                    s.sanitize();
                    s.dirty = true;
                    LOGGER.info("Post-Processing-Einstellungen geladen: " + FILE.getPath());
                    return s;
                }
            } catch (Exception e) {
                LOGGER.error("Post-Processing-Einstellungen fehlerhaft, nutze Defaults", e);
            }
        }
        PostProcessingSettings s = new PostProcessingSettings();
        s.save();
        return s;
    }

    /** Speichert den aktuellen, validierten Laufzeitstand dauerhaft. */
    public void save() {
        this.sanitize();
        this.dirty = true;
        try {
            File dir = FILE.getParentFile();
            if (dir != null && !dir.exists()) dir.mkdirs();
            try (FileWriter w = new FileWriter(FILE)) {
                GSON.toJson(this, w);
            }
        } catch (Exception e) {
            LOGGER.error("Post-Processing-Einstellungen konnten nicht gespeichert werden", e);
        }
    }

    /** Übernimmt alle Werte aus der JSON in DIESE Instanz (Reload-Hotkey) — Referenzen
        auf das Objekt (Context/Pässe) bleiben gültig. */
    public void reloadFromFile() {
        PostProcessingSettings fresh = load();
        this.exposure = fresh.exposure;
        this.temperature = fresh.temperature;
        this.tint = fresh.tint;
        this.tonemapOperator = fresh.tonemapOperator;
        this.lift = fresh.lift;
        this.gain = fresh.gain;
        this.shadows = fresh.shadows;
        this.midtones = fresh.midtones;
        this.highlights = fresh.highlights;
        this.contrast = fresh.contrast;
        this.brightness = fresh.brightness;
        this.saturation = fresh.saturation;
        this.vibrance = fresh.vibrance;
        this.gamma = fresh.gamma;
        this.aaMode = fresh.aaMode;
        this.debugMode = fresh.debugMode;
        this.taaHistoryWeight = fresh.taaHistoryWeight;
        this.taaMipBias = fresh.taaMipBias;
        this.bloomIntensity = fresh.bloomIntensity;
        this.bloomThreshold = fresh.bloomThreshold;
        this.vignette = fresh.vignette;
        this.sharpen = fresh.sharpen;
        this.dirty = true;
    }

    private void sanitize() {
        if (this.tonemapOperator == null) this.tonemapOperator = TonemapOperator.NONE;
        if (this.aaMode == null) this.aaMode = AntiAliasingMode.NONE;
        if (this.debugMode == null) this.debugMode = PostDebugMode.NONE;
        this.exposure = Math.clamp(this.exposure, EXPOSURE_MIN, EXPOSURE_MAX);
        this.gamma = Math.clamp(this.gamma, GAMMA_MIN, GAMMA_MAX);
        this.contrast = Math.clamp(this.contrast, MULTIPLIER_MIN, MULTIPLIER_MAX);
        this.saturation = Math.clamp(this.saturation, MULTIPLIER_MIN, MULTIPLIER_MAX);
        this.gain = Math.clamp(this.gain, MULTIPLIER_MIN, MULTIPLIER_MAX);
        this.shadows = Math.clamp(this.shadows, MULTIPLIER_MIN, MULTIPLIER_MAX);
        this.midtones = Math.clamp(this.midtones, MULTIPLIER_MIN, MULTIPLIER_MAX);
        this.highlights = Math.clamp(this.highlights, MULTIPLIER_MIN, MULTIPLIER_MAX);
        this.brightness = Math.clamp(this.brightness, OFFSET_MIN, OFFSET_MAX);
        this.lift = Math.clamp(this.lift, OFFSET_MIN, OFFSET_MAX);
        this.vibrance = Math.clamp(this.vibrance, COLOR_SHIFT_MIN, COLOR_SHIFT_MAX);
        this.temperature = Math.clamp(this.temperature, COLOR_SHIFT_MIN, COLOR_SHIFT_MAX);
        this.tint = Math.clamp(this.tint, COLOR_SHIFT_MIN, COLOR_SHIFT_MAX);
        this.taaHistoryWeight = Math.clamp(this.taaHistoryWeight, TAA_HISTORY_MIN, TAA_HISTORY_MAX);
        this.taaMipBias = Math.clamp(this.taaMipBias, TAA_MIP_BIAS_MIN, TAA_MIP_BIAS_MAX);
        this.sharpen = Math.clamp(this.sharpen, SHARPEN_MIN, SHARPEN_MAX);
    }

    /** true genau einmal nach jeder Änderung — der PostProcessor lädt dann das UBO neu. */
    public boolean consumeDirty() {
        boolean d = this.dirty;
        this.dirty = false;
        return d;
    }

    /* --- Getter (Pässe/UBO-Upload) --- */

    public float getExposure() { return this.exposure; }
    public float getTemperature() { return this.temperature; }
    public float getTint() { return this.tint; }
    public TonemapOperator getTonemapOperator() { return this.tonemapOperator; }
    public float getLift() { return this.lift; }
    public float getGain() { return this.gain; }
    public float getShadows() { return this.shadows; }
    public float getMidtones() { return this.midtones; }
    public float getHighlights() { return this.highlights; }
    public float getContrast() { return this.contrast; }
    public float getBrightness() { return this.brightness; }
    public float getSaturation() { return this.saturation; }
    public float getVibrance() { return this.vibrance; }
    public float getGamma() { return this.gamma; }
    public AntiAliasingMode getAaMode() { return this.aaMode; }
    public PostDebugMode getDebugMode() { return this.debugMode; }
    public float getTaaHistoryWeight() { return this.taaHistoryWeight; }
    public float getTaaMipBias() { return this.taaMipBias; }

    /** true bei den zeitlich akkumulierenden Modi (TAA/TAA_FXAA) — Jitter + Mip-Bias aktiv. */
    public boolean isTemporalAa() {
        return this.aaMode == AntiAliasingMode.TAA || this.aaMode == AntiAliasingMode.TAA_FXAA;
    }

    /** Effektive MSAA-Sample-Zahl des Szene-Framebuffers: nur im MSAA-Modus > 0
        (configured aus GameSettings.msaaSamples; 0 → Default 4). */
    public int effectiveMsaaSamples(int configured) {
        if (this.aaMode != AntiAliasingMode.MSAA) return 0;
        return configured > 0 ? configured : 4;
    }
    public float getBloomIntensity() { return this.bloomIntensity; }
    public float getBloomThreshold() { return this.bloomThreshold; }
    public float getVignette() { return this.vignette; }
    public float getSharpen() { return this.sharpen; }

    /* --- Setter (Runtime-Steuerung: Debug-Menü, später Biome/Dimension/Gameplay) --- */

    public void setExposure(float v) { this.exposure = Math.clamp(v, EXPOSURE_MIN, EXPOSURE_MAX); this.dirty = true; }
    public void setTemperature(float v) { this.temperature = Math.clamp(v, COLOR_SHIFT_MIN, COLOR_SHIFT_MAX); this.dirty = true; }
    public void setTint(float v) { this.tint = Math.clamp(v, COLOR_SHIFT_MIN, COLOR_SHIFT_MAX); this.dirty = true; }
    public void setTonemapOperator(TonemapOperator v) { this.tonemapOperator = v == null ? TonemapOperator.NONE : v; this.dirty = true; }
    public void setLift(float v) { this.lift = Math.clamp(v, OFFSET_MIN, OFFSET_MAX); this.dirty = true; }
    public void setGain(float v) { this.gain = Math.clamp(v, MULTIPLIER_MIN, MULTIPLIER_MAX); this.dirty = true; }
    public void setShadows(float v) { this.shadows = Math.clamp(v, MULTIPLIER_MIN, MULTIPLIER_MAX); this.dirty = true; }
    public void setMidtones(float v) { this.midtones = Math.clamp(v, MULTIPLIER_MIN, MULTIPLIER_MAX); this.dirty = true; }
    public void setHighlights(float v) { this.highlights = Math.clamp(v, MULTIPLIER_MIN, MULTIPLIER_MAX); this.dirty = true; }
    public void setContrast(float v) { this.contrast = Math.clamp(v, MULTIPLIER_MIN, MULTIPLIER_MAX); this.dirty = true; }
    public void setBrightness(float v) { this.brightness = Math.clamp(v, OFFSET_MIN, OFFSET_MAX); this.dirty = true; }
    public void setSaturation(float v) { this.saturation = Math.clamp(v, MULTIPLIER_MIN, MULTIPLIER_MAX); this.dirty = true; }
    public void setVibrance(float v) { this.vibrance = Math.clamp(v, COLOR_SHIFT_MIN, COLOR_SHIFT_MAX); this.dirty = true; }
    public void setGamma(float v) { this.gamma = Math.clamp(v, GAMMA_MIN, GAMMA_MAX); this.dirty = true; }
    public void setAaMode(AntiAliasingMode v) { this.aaMode = v == null ? AntiAliasingMode.NONE : v; this.dirty = true; }
    public void setTaaHistoryWeight(float v) { this.taaHistoryWeight = Math.clamp(v, TAA_HISTORY_MIN, TAA_HISTORY_MAX); this.dirty = true; }
    public void setTaaMipBias(float v) { this.taaMipBias = Math.clamp(v, TAA_MIP_BIAS_MIN, TAA_MIP_BIAS_MAX); this.dirty = true; }
    public void setDebugMode(PostDebugMode v) { this.debugMode = v == null ? PostDebugMode.NONE : v; this.dirty = true; }
    public void setBloomIntensity(float v) { this.bloomIntensity = v; this.dirty = true; }
    public void setBloomThreshold(float v) { this.bloomThreshold = v; this.dirty = true; }
    public void setVignette(float v) { this.vignette = v; this.dirty = true; }
    public void setSharpen(float v) { this.sharpen = Math.clamp(v, SHARPEN_MIN, SHARPEN_MAX); this.dirty = true; }
}
