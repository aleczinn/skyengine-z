package de.skyengine.core.resource;

import de.skyengine.core.file.Files;
import de.skyengine.shared.EngineInfo;

import java.io.IOException;
import java.net.JarURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.StandardCopyOption;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;

/** Globaler, frueh verfuegbarer Zugang zum aktiven Ressourcen-Stack. */
public final class Resources {
    private static ResourcePackRepository repository;
    private static ResourceManager manager;
    private static Path defaultGameRoot;

    public static synchronized void initialize() {
        initialize(List.of());
    }

    /** Initializes the shared resource stack without depending on client settings. */
    public static synchronized void initialize(List<String> selectedPacks) {
        if (manager != null) return;
        defaultGameRoot = resolveDefaultGameRoot();
        ResourceSource defaults = new DirectoryResourceSource(EngineInfo.CONTENT_NAMESPACE + "-default",
                defaultGameRoot, true);
        repository = new ResourcePackRepository();
        repository.refresh();
        manager = new ResourceManager(defaults);
        manager.setPacks(repository.selected(selectedPacks));
    }

    public static ResourceManager get() {
        if (manager == null) initialize();
        return manager;
    }

    /**
     * Physical root of the built-in gameplay data. Development runs use the source tree;
     * packaged distributions materialize the class-path resources once because the legacy
     * content bootstrap intentionally consumes directories rather than renderer resources.
     */
    public static Path defaultGameRoot() {
        if (manager == null) initialize();
        return defaultGameRoot;
    }

    public static ResourcePackRepository repository() {
        if (repository == null) initialize();
        return repository;
    }

    public static synchronized void activate(List<String> sourceNames) {
        repository.refresh();
        manager.setPacks(repository.selected(sourceNames));
    }

    /** Test-Hook fuer isolierte Resolver-Tests. */
    public static synchronized void install(ResourceManager replacement, ResourcePackRepository replacementRepository) {
        manager = replacement;
        repository = replacementRepository;
    }

    private static Path resolveDefaultGameRoot() {
        Path development = Path.of(Files.RESOURCES_PATH, "game").toAbsolutePath().normalize();
        if (java.nio.file.Files.isDirectory(development.resolve("blocks"))) return development;
        try {
            Path extracted = java.nio.file.Files.createTempDirectory("skyengine-game-content-");
            extracted.toFile().deleteOnExit();
            boolean copied = false;
            Enumeration<URL> roots = Resources.class.getClassLoader().getResources("game");
            while (roots.hasMoreElements()) {
                URL root = roots.nextElement();
                copied |= copyClasspathRoot(root, extracted);
            }
            if (!copied || !java.nio.file.Files.isDirectory(extracted.resolve("blocks"))) {
                throw new IOException("packaged game/blocks resources are absent");
            }
            return extracted;
        } catch (IOException failure) {
            throw new IllegalStateException("Built-in gameplay resources could not be located", failure);
        }
    }

    private static boolean copyClasspathRoot(URL root, Path target) throws IOException {
        if (root.getProtocol().equals("file")) {
            Path source;
            try { source = Path.of(root.toURI()); }
            catch (Exception invalid) { throw new IOException("Invalid classpath resource URL " + root, invalid); }
            copyTree(source, target);
            return true;
        }
        if (!root.getProtocol().equals("jar")) return false;
        JarURLConnection connection = (JarURLConnection) root.openConnection();
        connection.setUseCaches(false);
        URI jar = URI.create("jar:" + connection.getJarFileURL().toExternalForm());
        try (FileSystem fileSystem = FileSystems.newFileSystem(jar, new HashMap<>())) {
            copyTree(fileSystem.getPath("/game"), target);
        } catch (java.nio.file.FileSystemAlreadyExistsException alreadyOpen) {
            copyTree(FileSystems.getFileSystem(jar).getPath("/game"), target);
        }
        return true;
    }

    private static void copyTree(Path source, Path target) throws IOException {
        try (var paths = java.nio.file.Files.walk(source)) {
            for (Path path : paths.toList()) {
                Path destination = target.resolve(source.relativize(path).toString());
                if (java.nio.file.Files.isDirectory(path)) java.nio.file.Files.createDirectories(destination);
                else {
                    java.nio.file.Files.createDirectories(destination.getParent());
                    java.nio.file.Files.copy(path, destination, StandardCopyOption.REPLACE_EXISTING);
                    destination.toFile().deleteOnExit();
                }
            }
        }
    }

    private Resources() {}
}
