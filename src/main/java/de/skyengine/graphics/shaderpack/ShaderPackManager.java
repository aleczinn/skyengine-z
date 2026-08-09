package de.skyengine.graphics.shaderpack;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import de.skyengine.core.file.GameDirectory;
import de.skyengine.core.io.IDisposable;
import de.skyengine.utils.logging.LogManager;
import de.skyengine.utils.logging.Logger;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.List;

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
    private boolean reloadRequested;

    public void init() {
        this.active = loadConfigured();
        LOGGER.info("Shader-Pack aktiv: " + this.active.manifest().id);
    }

    public ShaderPack active() {
        return this.active;
    }

    public void register(Participant participant) {
        this.participants.add(participant);
    }

    public void unregister(Participant participant) {
        this.participants.remove(participant);
    }

    /** Safe from input code; actual GL work occurs at the next render-frame boundary. */
    public void requestReload() {
        this.reloadRequested = true;
    }

    public void reloadIfRequested() {
        if (!this.reloadRequested) return;
        this.reloadRequested = false;
        ShaderPack candidate;
        try {
            candidate = loadConfigured();
        } catch (RuntimeException e) {
            LOGGER.error("Shader-Pack konnte nicht geladen werden; bisheriges Pack bleibt aktiv", e);
            return;
        }

        List<Prepared> prepared = new ArrayList<>(this.participants.size());
        try {
            for (Participant participant : this.participants) prepared.add(participant.prepare(candidate));
        } catch (RuntimeException e) {
            for (Prepared resource : prepared) resource.dispose();
            LOGGER.error("Shader-Pack konnte nicht kompiliert werden; bisheriges Pack bleibt aktiv", e);
            return;
        }

        for (int i = 0; i < this.participants.size(); i++) {
            this.participants.get(i).activate(prepared.get(i));
        }
        this.active = candidate;
        LOGGER.info("Shader-Pack atomar neu geladen: " + candidate.manifest().id);
    }

    private ShaderPack loadConfigured() {
        Config config = readConfig();
        try {
            return this.loader.load(config.activePack);
        } catch (RuntimeException e) {
            if ("photon".equals(config.activePack)) throw e;
            LOGGER.error("Shader-Pack '" + config.activePack + "' ist ungültig; Photon-Fallback wird geladen", e);
            return this.loader.load("photon");
        }
    }

    private static Config readConfig() {
        if (CONFIG.isFile()) {
            try (FileReader reader = new FileReader(CONFIG)) {
                Config config = GSON.fromJson(reader, Config.class);
                if (config != null && config.schema == 1 && config.activePack != null) return config;
            } catch (Exception e) {
                LOGGER.error("Shader-Konfiguration ist ungültig; Photon wird verwendet", e);
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

    @Override
    public void dispose() {
        this.participants.clear();
        this.active = null;
    }

    private static final class Config {
        int schema = 1;
        String activePack = "photon";
    }
}
