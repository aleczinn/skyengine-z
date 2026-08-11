package de.skyengine.graphics.shaderpack;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import de.skyengine.core.file.GameDirectory;
import de.skyengine.core.io.IDisposable;
import de.skyengine.utils.logging.LogManager;
import de.skyengine.utils.logging.Logger;
import de.skyengine.graphics.shader.ShaderProgram;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Owns the active native shader pack and performs all-or-nothing renderer reloads. */
public final class ShaderPackManager implements IDisposable {
    public interface Prepared extends IDisposable {}

    public interface Participant {
        Prepared prepare(ShaderPack pack);
        void activate(Prepared prepared);
    }

    private static final Logger LOGGER = LogManager.getLogger(ShaderPackManager.class.getName());
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final File CONFIG = GameDirectory.resolve("config/shaders.json");
    private final List<Participant> participants = new ArrayList<>();
    private final ShaderPackLoader loader = new ShaderPackLoader();
    private ShaderPack active;
    private Config config;
    private boolean reloadRequested;
    private String requestedPack;

    public record PackOption(String id, String name) {}

    public void init() {
        this.config = readConfig();
        this.active = loadConfigured();
        LOGGER.info("Shader-Pack aktiv: " + this.active.manifest().id);
    }

    public ShaderPack active() {
        return this.active;
    }

    public List<ShaderPackManifest.Setting> activeSettings() {
        return this.active.manifest().settings;
    }

    public double settingValue(ShaderPackManifest.Setting setting) {
        Map<String, Double> values = this.config.packSettings.get(this.active.manifest().id);
        double value = values != null ? values.getOrDefault(setting.key, setting.defaultValue)
                : setting.defaultValue;
        return Math.clamp(value, setting.min, setting.max);
    }

    public void setSettingValue(ShaderPackManifest.Setting setting, double value) {
        double snapped = setting.min
                + Math.round((Math.clamp(value, setting.min, setting.max) - setting.min) / setting.step)
                * setting.step;
        this.config.packSettings.computeIfAbsent(this.active.manifest().id,
                ignored -> new LinkedHashMap<>()).put(setting.key,
                Math.clamp(snapped, setting.min, setting.max));
        if ("define".equals(setting.binding)) this.requestReload();
    }

    /** Entfernt alle Overrides des aktiven Packs. Danach liefern settingValue/program
        wieder ausschliesslich die in pack.json deklarierten Defaultwerte. */
    public void resetActiveSettings() {
        Map<String, Double> removed = this.config.packSettings.remove(this.active.manifest().id);
        if (removed == null || removed.isEmpty()) return;
        boolean compileSettingChanged = this.active.manifest().settings.stream()
                .anyMatch(setting -> "define".equals(setting.binding)
                        && removed.containsKey(setting.key));
        if (compileSettingChanged) this.requestReload();
    }

    /** Präprozessierte Quelle inklusive der für dieses Pack gespeicherten Compile-Settings. */
    public String program(ShaderPack pack, String key) {
        Map<String, String> defines = new LinkedHashMap<>();
        Map<String, Double> values = this.config.packSettings.get(pack.manifest().id);
        for (ShaderPackManifest.Setting setting : pack.manifest().settings) {
            if (!"define".equals(setting.binding)) continue;
            double value = values != null ? values.getOrDefault(setting.key, setting.defaultValue)
                    : setting.defaultValue;
            value = Math.clamp(value, setting.min, setting.max);
            if ("boolean".equals(setting.type)) {
                if (value >= 0.5) defines.put(setting.define, null);
            } else {
                defines.put(setting.define, Double.toString(value));
            }
        }
        return pack.program(key, defines);
    }

    /** Lädt alle vom aktiven Pack deklarierten Optionen in das gerade gebundene Programm. */
    public void applySettings(ShaderProgram program) {
        for (ShaderPackManifest.Setting setting : this.active.manifest().settings) {
            if (!"uniform".equals(setting.binding)) continue;
            int location = program.getUniformLocation(setting.uniform);
            if (location >= 0) program.setUniformf(location, (float) this.settingValue(setting));
        }
    }

    public void saveSettings() {
        saveConfig(this.active.manifest().id);
    }

    public void register(Participant participant) {
        this.participants.add(participant);
    }

    public void unregister(Participant participant) {
        this.participants.remove(participant);
    }

    /** Safe from input code; actual GL work occurs at the next render-frame boundary. */
    public void requestReload() {
        this.requestedPack = null;
        this.reloadRequested = true;
    }

    /** Wählt ein Pack atomar an der nächsten Frame-Grenze. Persistiert wird erst nach
        erfolgreichem Laden und Kompilieren, damit ein defektes Pack keinen Start-Loop erzeugt. */
    public void requestPack(String id) {
        if (id == null || !id.matches("[a-z0-9_.-]+")) {
            throw new IllegalArgumentException("Invalid shader-pack id: " + id);
        }
        this.requestedPack = id;
        this.reloadRequested = true;
    }

    /** Eingebautes Vanilla-Pack plus valide externe Verzeichnisse, stabil alphabetisch. */
    public List<PackOption> availablePacks() {
        Map<String, PackOption> packs = new LinkedHashMap<>();
        ShaderPack builtin = this.loader.load("vibrant_visuals");
        packs.put(builtin.manifest().id,
                new PackOption(builtin.manifest().id, builtin.manifest().name));
        Path root = ShaderPack.externalDirectory().toPath();
        if (Files.isDirectory(root)) {
            try (var entries = Files.list(root)) {
                entries.filter(Files::isDirectory)
                        .map(path -> path.getFileName().toString())
                        .sorted()
                        .forEach(id -> {
                            try {
                                ShaderPack pack = this.loader.load(id);
                                packs.put(id, new PackOption(id, pack.manifest().name));
                            } catch (RuntimeException e) {
                                LOGGER.warning("Shader-Pack '" + id + "' wird in der Auswahl übersprungen: "
                                        + e.getMessage());
                            }
                        });
            } catch (Exception e) {
                LOGGER.error("Shader-Pack-Verzeichnis konnte nicht gelesen werden", e);
            }
        }
        List<PackOption> result = new ArrayList<>(packs.values());
        result.sort(Comparator.comparing(PackOption::name, String.CASE_INSENSITIVE_ORDER));
        return List.copyOf(result);
    }

    public void reloadIfRequested() {
        if (!this.reloadRequested) return;
        this.reloadRequested = false;
        ShaderPack candidate;
        try {
            candidate = this.requestedPack == null
                    ? loadConfigured()
                    : this.loader.load(this.requestedPack);
        } catch (RuntimeException e) {
            this.requestedPack = null;
            LOGGER.error("Shader-Pack konnte nicht geladen werden; bisheriges Pack bleibt aktiv", e);
            return;
        }

        List<Prepared> prepared = new ArrayList<>(this.participants.size());
        try {
            for (Participant participant : this.participants) prepared.add(participant.prepare(candidate));
        } catch (RuntimeException e) {
            for (Prepared resource : prepared) resource.dispose();
            this.requestedPack = null;
            LOGGER.error("Shader-Pack konnte nicht kompiliert werden; bisheriges Pack bleibt aktiv", e);
            return;
        }

        for (int i = 0; i < this.participants.size(); i++) {
            this.participants.get(i).activate(prepared.get(i));
        }
        this.active = candidate;
        this.requestedPack = null;
        saveConfig(candidate.manifest().id);
        LOGGER.info("Shader-Pack atomar neu geladen: " + candidate.manifest().id);
    }

    private ShaderPack loadConfigured() {
        /* Einmalige interne Alias-Migration nach der Umbenennung des eingebauten Packs.
           Persistiert wird wie bisher erst bei einer erfolgreichen Auswahl/Speicherung. */
        if ("photon".equals(this.config.activePack)) this.config.activePack = "vibrant_visuals";
        try {
            return this.loader.load(this.config.activePack);
        } catch (RuntimeException e) {
            if ("vibrant_visuals".equals(this.config.activePack)) throw e;
            LOGGER.error("Shader-Pack '" + this.config.activePack
                    + "' ist ungültig; Vibrant-Visuals-Fallback wird geladen", e);
            return this.loader.load("vibrant_visuals");
        }
    }

    private static Config readConfig() {
        if (CONFIG.isFile()) {
            try (FileReader reader = new FileReader(CONFIG)) {
                Config config = GSON.fromJson(reader, Config.class);
                if (config != null && config.schema == 1 && config.activePack != null) {
                    if (config.packSettings == null) config.packSettings = new LinkedHashMap<>();
                    return config;
                }
            } catch (Exception e) {
                LOGGER.error("Shader-Konfiguration ist ungültig; Vibrant Visuals wird verwendet", e);
            }
        }
        Config config = new Config();
        File parent = CONFIG.getParentFile();
        if (parent != null) parent.mkdirs();
        try (FileWriter writer = new FileWriter(CONFIG)) {
            GSON.toJson(config, writer);
        } catch (Exception e) {
            LOGGER.error("Shader-Konfiguration konnte nicht angelegt werden", e);
        }
        return config;
    }

    private void saveConfig(String id) {
        this.config.activePack = id;
        File parent = CONFIG.getParentFile();
        if (parent != null) parent.mkdirs();
        try (FileWriter writer = new FileWriter(CONFIG)) {
            GSON.toJson(this.config, writer);
        } catch (Exception e) {
            LOGGER.error("Shader-Konfiguration konnte nicht gespeichert werden", e);
        }
    }

    @Override
    public void dispose() {
        this.participants.clear();
        this.active = null;
    }

    private static final class Config {
        int schema = 1;
        String activePack = "vibrant_visuals";
        Map<String, Map<String, Double>> packSettings = new LinkedHashMap<>();
    }
}
