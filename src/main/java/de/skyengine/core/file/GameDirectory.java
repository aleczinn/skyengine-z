package de.skyengine.core.file;

import de.skyengine.utils.logging.LogManager;
import de.skyengine.utils.logging.Logger;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.stream.Stream;

/**
 * Zentraler Spiel-Root-Ordner (analog {@code .minecraft}): {@code %APPDATA%\.skyengine}
 * (Fallback {@code user.home/.skyengine} auf Nicht-Windows). Enthält {@code config/},
 * {@code saves/} und {@code screenshots/}; {@code debug/} + {@code debug-maps/} bleiben
 * bewusst als Dev-Werkzeuge im Arbeitsverzeichnis.
 *
 * <p><b>Einmalige Migration:</b> Existiert der Root noch nicht, werden beim ersten Zugriff
 * vorhandene {@code config/}, {@code saves/}, {@code screenshots/} aus dem Arbeitsverzeichnis
 * hineinkopiert (Marker = Existenz des Root-Ordners). Läuft im statischen Lazy-Init, weil
 * {@code GameSettings.get()} sehr früh liest — vor jedem Engine-Init-Hook.
 */
public final class GameDirectory {

    private static final Logger LOGGER = LogManager.getLogger(GameDirectory.class.getName());
    private static final String[] MIGRATED_DIRS = {"config", "saves", "screenshots"};

    private static File root;

    /** Der Spiel-Root-Ordner (beim ersten Aufruf: anlegen + ggf. Migration). */
    public static synchronized File root() {
        if (root == null) {
            root = resolveRoot();
            if (!root.exists()) {
                migrate(root);
            }
        }
        return root;
    }

    /** Unterordner/Datei im Root, z.B. {@code resolve("saves")}. */
    public static File resolve(String sub) {
        return new File(root(), sub);
    }

    private static File resolveRoot() {
        String appData = System.getenv("APPDATA");
        File base = appData != null && !appData.isBlank() ? new File(appData) : new File(System.getProperty("user.home"));
        return new File(base, ".skyengine");
    }

    /** Root anlegen und Altbestand aus dem Arbeitsverzeichnis EINMALIG hinüberkopieren. */
    private static void migrate(File target) {
        if (!target.mkdirs()) {
            LOGGER.error("Spiel-Ordner konnte nicht angelegt werden: " + target.getPath());
            return;
        }
        for (String dir : MIGRATED_DIRS) {
            File source = new File(dir);
            if (!source.isDirectory()) continue;
            try {
                copyRecursive(source.toPath(), new File(target, dir).toPath());
                LOGGER.info("Migriert: " + source.getPath() + " -> " + new File(target, dir).getPath());
            } catch (IOException e) {
                LOGGER.error("Migration fehlgeschlagen für " + source.getPath(), e);
            }
        }
        LOGGER.info("Spiel-Ordner: " + target.getPath());
    }

    private static void copyRecursive(Path source, Path target) throws IOException {
        try (Stream<Path> walk = Files.walk(source)) {
            for (Path path : (Iterable<Path>) walk::iterator) {
                Path dest = target.resolve(source.relativize(path).toString());
                if (Files.isDirectory(path)) {
                    Files.createDirectories(dest);
                } else {
                    Files.createDirectories(dest.getParent());
                    Files.copy(path, dest, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private GameDirectory() {}
}
