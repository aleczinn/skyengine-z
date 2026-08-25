package de.skyengine.core.file;

import de.skyengine.core.SkyEngine;
import de.skyengine.utils.logging.LogManager;
import de.skyengine.utils.logging.Logger;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

/**
 * Zentraler Spiel-Root-Ordner (analog {@code .minecraft}): {@code %APPDATA%/.voxelstories}
 * (Fallback {@code user.home/.voxelstories} auf Nicht-Windows). Enthält {@code config/},
 * {@code saves/} und {@code screenshots/}; {@code debug/} + {@code debug-maps/} bleiben
 * bewusst als Dev-Werkzeuge im Arbeitsverzeichnis. Tests und Werkzeuge koennen den Root mit
 * {@code -Dskyengine.gameDirectory=<pfad>} vollstaendig isolieren.
 *
 * <p><b>Einmalige Migration:</b> Der komplette bisherige {@code .skyengine}-Ordner wird
 * konfliktfrei kopiert und als Rückfall beibehalten. Separate Marker erlauben eine sichere
 * Wiederaufnahme und anschließend die Migration alter Arbeitsverzeichnis-Daten. Läuft im
 * statischen Lazy-Init, weil
 * {@code GameSettings.get()} sehr früh liest — vor jedem Engine-Init-Hook.
 */
public final class GameDirectory {

    public static final String ROOT_PROPERTY = "skyengine.gameDirectory";
    private static final Logger LOGGER = LogManager.getLogger(GameDirectory.class.getName());
    private static final String[] MIGRATED_DIRS = {"config", "saves", "screenshots"};
    private static final String LEGACY_ROOT_MARKER = ".migration-from-skyengine-v1";
    private static final String WORKING_DIR_MARKER = ".migration-from-working-directory-v1";

    private static File root;

    /** Der Spiel-Root-Ordner (beim ersten Aufruf: anlegen + ggf. Migration). */
    public static synchronized File root() {
        if (root == null) {
            root = resolveRoot();
            ensureAndMigrate(root);
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
        File base = appData != null && !appData.isBlank() ? new File(appData) : new File(System.getProperty("user.home"));
        return new File(base, SkyEngine.GAME_DATA_DIRECTORY_NAME);
    }

    /** Root anlegen und Altbestände markerbasiert sowie ohne Überschreiben kopieren. */
    private static void ensureAndMigrate(File target) {
        if (!target.isDirectory() && !target.mkdirs()) {
            LOGGER.error("Spiel-Ordner konnte nicht angelegt werden: " + target.getPath());
            return;
        }
        String configured = System.getProperty(ROOT_PROPERTY);
        if (configured != null && !configured.isBlank()) {
            LOGGER.info("Isolierter Spiel-Ordner: " + target.getPath());
            return;
        }
        migrateTree(new File(target.getParentFile(), ".skyengine"), target,
                new File(target, LEGACY_ROOT_MARKER));
        migrateWorkingDirectories(target);
        LOGGER.info("Spiel-Ordner: " + target.getPath());
    }

    private static void migrateWorkingDirectories(File target) {
        File marker = new File(target, WORKING_DIR_MARKER);
        if (marker.isFile()) return;
        boolean successful = true;
        for (String dir : MIGRATED_DIRS) {
            File source = new File(dir);
            if (!source.isDirectory()) continue;
            try {
                copyRecursive(source.toPath(), new File(target, dir).toPath());
                LOGGER.info("Migriert: " + source.getPath() + " -> " + new File(target, dir).getPath());
            } catch (IOException e) {
                successful = false;
                LOGGER.error("Migration fehlgeschlagen für " + source.getPath(), e);
            }
        }
        if (successful) writeMarker(marker);
    }

    private static void migrateTree(File source, File target, File marker) {
        if (marker.isFile()) return;
        if (!source.isDirectory()) {
            writeMarker(marker);
            return;
        }
        try {
            copyRecursive(source.toPath(), target.toPath());
            writeMarker(marker);
            LOGGER.info("Migriert (Quelle bleibt erhalten): " + source.getPath() + " -> " + target.getPath());
        } catch (IOException e) {
            LOGGER.error("Migration fehlgeschlagen fuer " + source.getPath(), e);
        }
    }

    private static void writeMarker(File marker) {
        try {
            Files.writeString(marker.toPath(), "completed\n");
        } catch (IOException e) {
            LOGGER.error("Migrationsmarker konnte nicht geschrieben werden: " + marker.getPath(), e);
        }
    }

    static void copyRecursive(Path source, Path target) throws IOException {
        try (Stream<Path> walk = Files.walk(source)) {
            for (Path path : (Iterable<Path>) walk::iterator) {
                Path dest = target.resolve(source.relativize(path).toString());
                if (Files.isDirectory(path)) {
                    Files.createDirectories(dest);
                } else if (!Files.exists(dest)) {
                    Files.createDirectories(dest.getParent());
                    Files.copy(path, dest);
                }
            }
        }
    }

    private GameDirectory() {}
}
