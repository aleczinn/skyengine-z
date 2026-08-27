package de.skyengine.core.file;

import de.skyengine.core.SkyEngine;
import de.skyengine.utils.logging.LogManager;
import de.skyengine.utils.logging.Logger;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Zentraler Spiel-Root-Ordner (analog {@code .minecraft}): {@code %APPDATA%/.voxelstories}
 * (Fallback {@code user.home/.voxelstories} auf Nicht-Windows). Enthält unter anderem
 * {@code config/}, {@code saves/} und {@code screenshots/}. Tests und Werkzeuge können den
 * Root mit {@code -Dskyengine.gameDirectory=<pfad>} vollständig isolieren.
 */
public final class GameDirectory {

    public static final String ROOT_PROPERTY = "skyengine.gameDirectory";
    private static final Logger LOGGER = LogManager.getLogger(GameDirectory.class.getName());
    private static final String[] OBSOLETE_MARKERS = {
            ".migration-from-skyengine-v1",
            ".migration-from-working-directory-v1",
            "bin/structures/.default-structure-v1",
            "bin/structures/.default-structures-v1"
    };

    private static File root;

    /** Der Spiel-Root-Ordner; wird beim ersten Aufruf angelegt. */
    public static synchronized File root() {
        if (root == null) {
            root = resolveRoot();
            ensureRoot(root);
        }
        return root;
    }

    /** Unterordner/Datei im Root, z.B. {@code resolve("saves")}. */
    public static File resolve(String sub) {
        return new File(root(), sub);
    }

    private static File resolveRoot() {
        String configured = System.getProperty(ROOT_PROPERTY);
        if (configured != null && !configured.isBlank()) {
            return new File(configured).getAbsoluteFile();
        }
        String appData = System.getenv("APPDATA");
        File base = appData != null && !appData.isBlank()
                ? new File(appData) : new File(System.getProperty("user.home"));
        return new File(base, SkyEngine.GAME_DATA_DIRECTORY_NAME);
    }

    /** Root anlegen und veraltete technische Marker früherer Alpha-Versionen entfernen. */
    private static void ensureRoot(File target) {
        if (!target.isDirectory() && !target.mkdirs()) {
            LOGGER.error("Spiel-Ordner konnte nicht angelegt werden: " + target.getPath());
            return;
        }
        cleanupObsoleteMarkers(target.toPath());
        String configured = System.getProperty(ROOT_PROPERTY);
        if (configured != null && !configured.isBlank()) {
            LOGGER.info("Isolierter Spiel-Ordner: " + target.getPath());
        } else {
            LOGGER.info("Spiel-Ordner: " + target.getPath());
        }
    }

    static void cleanupObsoleteMarkers(Path root) {
        for (String relative : OBSOLETE_MARKERS) {
            Path marker = root.resolve(relative);
            try {
                Files.deleteIfExists(marker);
            } catch (IOException e) {
                LOGGER.warning("Veralteter Marker konnte nicht entfernt werden: "
                        + marker + " (" + e.getMessage() + ")");
            }
        }
    }

    private GameDirectory() {}
}
