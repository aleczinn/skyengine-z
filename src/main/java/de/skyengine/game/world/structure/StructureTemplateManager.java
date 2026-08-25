package de.skyengine.game.world.structure;

import de.skyengine.core.file.GameDirectory;
import de.skyengine.core.resource.ResourceId;
import de.skyengine.core.resource.Resources;
import de.skyengine.game.world.block.Identifier;
import de.skyengine.utils.logging.LogManager;
import de.skyengine.utils.logging.Logger;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Globaler Structure-Katalog; zur Laufzeit ist ausschliesslich bin/structures kanonisch. */
public final class StructureTemplateManager {
    private static final Logger LOGGER = LogManager.getLogger(StructureTemplateManager.class.getName());
    private static final String RESOURCE_PREFIX = "worldgen/structures/";

    private final Path externalRoot;
    private final Map<Identifier, Path> external = new ConcurrentHashMap<>();
    private final Map<Identifier, StructureTemplate> cache = new ConcurrentHashMap<>();

    public StructureTemplateManager() {
        this(GameDirectory.resolve("bin/structures").toPath(), GameDirectory.resolve("saves"), true);
    }

    StructureTemplateManager(Path externalRoot, File savesRoot) {
        this(externalRoot, savesRoot, false);
    }

    StructureTemplateManager(Path externalRoot, File savesRoot, boolean installDefaults) {
        this.externalRoot = externalRoot.toAbsolutePath().normalize();
        try {
            Files.createDirectories(this.externalRoot);
            migrateLegacyAuthoring(savesRoot);
            if (installDefaults) DefaultStructureInstaller.install(this.externalRoot);
            refreshExternalIndex();
        } catch (IOException e) {
            throw new IllegalStateException("Globaler Structure-Katalog konnte nicht initialisiert werden", e);
        }
    }

    public Path externalRoot() { return this.externalRoot; }

    public StructureTemplate get(Identifier id) throws IOException {
        StructureTemplate cached = this.cache.get(id);
        if (cached != null) return cached;
        Path file = this.external.get(id);
        StructureTemplate loaded = file == null ? null : StructureSerializer.read(file, id);
        if (loaded != null) this.cache.put(id, loaded);
        return loaded;
    }

    /** Dateipfad aus der Chat-Oberflaeche oder alte namespaced ID. */
    public synchronized StructureTemplate get(String reference) throws IOException {
        refreshExternalIndex();
        if (reference.indexOf(':') >= 0) return get(referenceId(reference));
        String path = normalizeReferencePath(reference);
        for (Map.Entry<Identifier, Path> entry : this.external.entrySet()) {
            String candidate = this.externalRoot.relativize(entry.getValue()).toString().replace('\\', '/');
            if (candidate.equals(path)) return get(entry.getKey());
        }
        return null;
    }

    /** Erzeugt fuer einen neuen relativen Pfad die interne Default-Namespace-ID. */
    public Identifier idForNewReference(String reference) throws IOException {
        if (reference.indexOf(':') >= 0) return referenceId(reference);
        String path = normalizeReferencePath(reference);
        return Identifier.of("skyengine:" + path.substring(0, path.length() - ".structure".length()));
    }

    /** Sichtbare, reale Pfade relativ zu bin/structures. */
    public synchronized List<String> references() throws IOException {
        refreshExternalIndex();
        return this.external.values().stream()
                .map(path -> this.externalRoot.relativize(path).toString().replace('\\', '/'))
                .sorted().toList();
    }

    /** Pack-Ressource ohne externes Overlay. */
    public static StructureTemplate loadResource(Identifier id) throws IOException {
        ResourceId resource = new ResourceId(id.namespace(), RESOURCE_PREFIX + id.path() + ".structure");
        var match = Resources.get().find(resource);
        if (match.isEmpty()) return null;
        try (InputStream in = match.get().open()) {
            return StructureSerializer.read(in, id);
        }
    }

    public synchronized void saveAuthored(StructureTemplate template, boolean overwrite) throws IOException {
        Path target = externalPath(template.id());
        if (Files.isRegularFile(target)) {
            StructureTemplate existing = StructureSerializer.read(target, null);
            if (!existing.id().equals(template.id())) {
                throw new IOException("Structure-Pfad wird bereits von " + existing.id() + " verwendet: " + target);
            }
            if (!overwrite) throw new IOException("Structure existiert bereits: " + template.id());
        }
        StructureSerializer.write(target, template);
        this.external.put(template.id(), target);
        this.cache.put(template.id(), template);
    }

    public synchronized List<Identifier> ids() throws IOException {
        refreshExternalIndex();
        ArrayList<Identifier> result = new ArrayList<>(this.external.keySet());
        result.sort(Comparator.comparing(Identifier::toString));
        return List.copyOf(result);
    }

    /** Unveraenderlicher Katalogstand fuer eine geoeffnete Welt und deren Worldgen. */
    public synchronized Snapshot snapshot() throws IOException {
        Map<Identifier, StructureTemplate> templates = new LinkedHashMap<>();
        for (Identifier id : ids()) {
            StructureTemplate template = get(id);
            if (template != null) templates.put(id, template);
        }
        Path catalogPath = this.externalRoot.getParent().resolve("worldgen/tree_templates.json");
        return new Snapshot(templates, TreeTemplateCatalog.load(catalogPath));
    }

    private void refreshExternalIndex() throws IOException {
        Map<Identifier, Path> discovered = new LinkedHashMap<>();
        if (Files.isDirectory(this.externalRoot)) {
            try (var walk = Files.walk(this.externalRoot)) {
                for (Path file : walk.filter(Files::isRegularFile)
                        .filter(path -> path.getFileName().toString().endsWith(".structure")).toList()) {
                    try {
                        StructureTemplate template = StructureSerializer.read(file, null);
                        Path expected = externalPath(template.id());
                        if (!file.toAbsolutePath().normalize().equals(expected)) {
                            throw new IOException("Structure " + template.id() + " liegt am falschen Pfad: " + file
                                    + " (erwartet: " + expected + ")");
                        }
                        Path previous = discovered.putIfAbsent(template.id(), expected);
                        if (previous != null && !previous.equals(expected)) {
                            throw new IOException("Doppelte globale Structure-ID " + template.id());
                        }
                    } catch (IOException e) {
                        LOGGER.warning("Ungueltige globale Structure wird ignoriert: " + file + " (" + e.getMessage() + ")");
                    }
                }
            }
        }
        this.external.clear();
        this.external.putAll(discovered);
        this.cache.clear();
    }

    private Path externalPath(Identifier id) throws IOException {
        if (!id.path().matches("[a-z0-9._/-]+") || id.path().contains("..")) {
            throw new IOException("Ungueltige Structure-ID " + id);
        }
        Path target = this.externalRoot.resolve(id.path() + ".structure").normalize();
        if (!target.startsWith(this.externalRoot)) throw new IOException("Structure-Pfad verlaesst den globalen Ordner");
        return target;
    }

    private static Identifier referenceId(String reference) throws IOException {
        String value = reference.replace('\\', '/');
        if (value.endsWith(".structure")) value = value.substring(0, value.length() - ".structure".length());
        try {
            return Identifier.of(value);
        } catch (IllegalArgumentException e) {
            throw new IOException("Ungueltige Structure-ID: " + reference, e);
        }
    }

    private static String normalizeReferencePath(String reference) throws IOException {
        String value = reference.replace('\\', '/');
        if (!value.endsWith(".structure")) value += ".structure";
        if (value.startsWith("/") || value.contains("//") || value.contains("..")
                || !value.matches("[a-z0-9._/-]+\\.structure")) {
            throw new IOException("Ungueltiger Structure-Pfad: " + reference);
        }
        return value;
    }

    private void migrateLegacyAuthoring(File savesRoot) throws IOException {
        if (savesRoot == null || !savesRoot.isDirectory()) return;
        Path legacySegment = Path.of("datapacks", "voxel_stories_authoring", "data");
        try (var walk = Files.walk(savesRoot.toPath())) {
            for (Path source : walk.filter(Files::isRegularFile)
                    .filter(path -> path.toAbsolutePath().normalize().toString()
                            .contains(legacySegment.toString()))
                    .filter(path -> path.getFileName().toString().endsWith(".structure")).toList()) {
                try {
                    StructureTemplate template = StructureSerializer.read(source, null);
                    Path target = externalPath(template.id());
                    if (!Files.exists(target)) {
                        Files.createDirectories(target.getParent());
                        Files.copy(source, target);
                        LOGGER.info("Weltbezogene Structure global migriert: " + source + " -> " + target);
                    } else {
                        StructureTemplate existing = StructureSerializer.read(target, null);
                        if (!existing.fingerprint().equals(template.fingerprint())) {
                            LOGGER.warning("Structure-Migrationskonflikt, Quelldatei bleibt erhalten: " + source);
                        }
                    }
                } catch (IOException e) {
                    LOGGER.warning("Structure konnte nicht global migriert werden: " + source + " (" + e.getMessage() + ")");
                }
            }
        }
    }

    public static final class Snapshot {
        private final Map<Identifier, StructureTemplate> templates;
        private final int fingerprint;
        private final TreeTemplateCatalog treeCatalog;

        private Snapshot(Map<Identifier, StructureTemplate> templates, TreeTemplateCatalog treeCatalog) {
            this.templates = Map.copyOf(templates);
            this.treeCatalog = treeCatalog;
            int hash = 1;
            for (Map.Entry<Identifier, StructureTemplate> entry : templates.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey(Comparator.comparing(Identifier::toString))).toList()) {
                hash = 31 * hash + entry.getKey().hashCode();
                hash = 31 * hash + entry.getValue().fingerprint().hashCode();
            }
            this.fingerprint = 31 * hash + treeCatalog.fingerprint();
        }

        public StructureTemplate get(Identifier id) { return this.templates.get(id); }
        public Collection<Identifier> ids() { return this.templates.keySet(); }
        public int fingerprint() { return this.fingerprint; }
        public TreeTemplateCatalog treeCatalog() { return this.treeCatalog; }
    }
}
