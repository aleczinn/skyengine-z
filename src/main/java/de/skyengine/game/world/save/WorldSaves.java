package de.skyengine.game.world.save;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import de.skyengine.game.world.generator.generators.AlphaWorldGeneratorV2;
import de.skyengine.game.world.block.Identifier;
import de.skyengine.utils.logging.LogManager;
import de.skyengine.utils.logging.Logger;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Savegame-Verwaltung unter {@code ./saves/<ordner>/level.json} (Muster: GameSettings).
 * Läuft ausschließlich auf dem Render-Thread (Menü-Callbacks bzw. Welt-Austritt).
 */
public final class WorldSaves {

    private static final Logger LOGGER = LogManager.getLogger(WorldSaves.class.getName());
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    /* Liegt im konfigurierten Spiel-Root (standardmäßig %APPDATA%\.voxelstories). */
    private static final File ROOT = de.skyengine.core.file.GameDirectory.resolve("saves");

    /** Ein Savegame: Ordnername (eindeutig) + geladene Metadaten. */
    public record WorldSave(String dirName, LevelData level) {}

    /** Alle Welten, zuletzt gespielte zuerst. Fehlerhafte level.json werden übersprungen. */
    public static List<WorldSave> list() {
        List<WorldSave> result = new ArrayList<>();
        File[] dirs = ROOT.listFiles(File::isDirectory);
        if (dirs == null) return result;
        for (File dir : dirs) {
            File file = new File(dir, "level.json");
            if (!file.isFile()) continue;
            try (FileReader r = new FileReader(file)) {
                LevelData level = GSON.fromJson(r, LevelData.class);
                if (level != null && level.name != null) {
                    result.add(new WorldSave(dir.getName(), level));
                }
            } catch (Exception e) {
                LOGGER.error("level.json fehlerhaft, überspringe: " + file.getPath(), e);
            }
        }
        result.sort(Comparator.comparingLong((WorldSave s) -> s.level().lastPlayed).reversed());
        return result;
    }

    /** Legt eine neue Welt an (Ordnername aus dem Namen, bei Kollision -2, -3, …). */
    public static WorldSave create(String name, int seed) {
        String base = sanitizeDirName(name);
        String dirName = base;
        for (int i = 2; new File(ROOT, dirName).exists(); i++) {
            dirName = base + "-" + i;
        }
        LevelData level = new LevelData();
        level.name = name;
        level.seed = seed;
        level.created = System.currentTimeMillis();
        level.lastPlayed = level.created;
        level.formatVersion = 3;
        level.worldType = "default";
        level.generator = "alpha_v2";
        level.generatorVersion = AlphaWorldGeneratorV2.VERSION;
        LevelData.DimensionData overworld = new LevelData.DimensionData();
        overworld.seed = seed;
        overworld.generator = Identifier.of("alpha_v2").toString();
        overworld.generatorVersion = 2;
        level.dimensions.put(Identifier.of("overworld").toString(), overworld);
        WorldSave save = new WorldSave(dirName, level);
        save(save);
        return save;
    }

    /** Absoluter Ordner eines Savegames (für player.dat/region neben der level.json). */
    public static File dir(String dirName) {
        return new File(ROOT, dirName);
    }

    /** Schreibt die level.json des Savegames (legt den Ordner bei Bedarf an). */
    public static void save(WorldSave save) {
        saveInDirectory(save, new File(ROOT, save.dirName()));
    }

    /** Schreibt Metadaten in einen expliziten Save-Root (Server, Tests, alternative Hosts). */
    public static void saveInDirectory(WorldSave save, File dir) {
        if (!dir.exists() && !dir.mkdirs()) {
            LOGGER.error("Save-Ordner konnte nicht angelegt werden: " + dir.getPath());
            return;
        }
        try (FileWriter w = new FileWriter(new File(dir, "level.json"))) {
            GSON.toJson(save.level(), w);
        } catch (Exception e) {
            LOGGER.error("level.json konnte nicht geschrieben werden: " + save.dirName(), e);
        }
    }

    /** Löscht das Savegame-Verzeichnis rekursiv. */
    public static void delete(WorldSave save) {
        deleteRecursive(new File(ROOT, save.dirName()));
    }

    private static void deleteRecursive(File file) {
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) deleteRecursive(child);
        }
        if (!file.delete() && file.exists()) {
            LOGGER.error("Konnte nicht löschen: " + file.getPath());
        }
    }

    /** Dateisystem-sicherer Ordnername (Kleinbuchstaben/Ziffern/-_, nie leer). */
    private static String sanitizeDirName(String name) {
        String clean = name.toLowerCase().replaceAll("[^a-z0-9-_]", "_").replaceAll("_{2,}", "_");
        clean = clean.replaceAll("^_+|_+$", "");
        return clean.isEmpty() ? "welt" : clean;
    }

    private WorldSaves() {}
}
