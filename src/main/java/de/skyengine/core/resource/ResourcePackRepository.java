package de.skyengine.core.resource;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import de.skyengine.core.file.GameDirectory;
import de.skyengine.utils.logging.LogManager;
import de.skyengine.utils.logging.Logger;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Scannt den spielnamensabhaengigen {@code resourcepacks}-Ordner. */
public final class ResourcePackRepository {
    private static final Logger LOGGER = LogManager.getLogger(ResourcePackRepository.class.getName());
    private final Path directory;
    private final Map<String, ResourcePack> packs = new LinkedHashMap<>();

    public ResourcePackRepository() {
        this(GameDirectory.resolve("resourcepacks").toPath());
    }

    public ResourcePackRepository(Path directory) {
        this.directory = directory.toAbsolutePath().normalize();
    }

    public Path directory() { return this.directory; }

    public synchronized List<ResourcePack> refresh() {
        this.packs.clear();
        try {
            Files.createDirectories(this.directory);
            try (var stream = Files.list(this.directory)) {
                for (Path entry : stream
                        .filter(p -> Files.isDirectory(p) || p.getFileName().toString().toLowerCase().endsWith(".zip"))
                        .sorted(Comparator.comparing(p -> p.getFileName().toString().toLowerCase()))
                        .toList()) {
                    ResourcePack pack = read(entry);
                    this.packs.put(pack.sourceName(), pack);
                }
            }
        } catch (IOException e) {
            LOGGER.error("Ressourcenpaket-Ordner konnte nicht gelesen werden: " + this.directory, e);
        }
        return List.copyOf(this.packs.values());
    }

    public synchronized List<ResourcePack> all() {
        return List.copyOf(this.packs.values());
    }

    public synchronized ResourcePack get(String sourceName) {
        return this.packs.get(sourceName);
    }

    public synchronized List<ResourcePack> selected(List<String> sourceNames) {
        List<ResourcePack> result = new ArrayList<>();
        if (sourceNames == null) return result;
        for (String name : sourceNames) {
            ResourcePack pack = this.packs.get(name);
            if (pack != null && pack.valid()) result.add(pack);
        }
        return result;
    }

    private ResourcePack read(Path entry) {
        String sourceName = entry.getFileName().toString();
        try {
            JsonObject root;
            ResourceSource source;
            if (Files.isDirectory(entry)) {
                Path manifest = entry.resolve("pack.json");
                if (!Files.isRegularFile(manifest)) return invalid(sourceName, entry, "pack.json fehlt");
                try (Reader reader = Files.newBufferedReader(manifest, StandardCharsets.UTF_8)) {
                    root = JsonParser.parseReader(reader).getAsJsonObject();
                }
                source = new DirectoryResourceSource(sourceName, entry, false);
            } else {
                try (ZipFile zip = new ZipFile(entry.toFile())) {
                    ZipEntry manifest = zip.getEntry("pack.json");
                    if (manifest == null || manifest.isDirectory()) return invalid(sourceName, entry, "pack.json fehlt");
                    try (Reader reader = new java.io.InputStreamReader(zip.getInputStream(manifest), StandardCharsets.UTF_8)) {
                        root = JsonParser.parseReader(reader).getAsJsonObject();
                    }
                }
                source = new ZipResourceSource(sourceName, entry);
            }
            JsonObject meta = root.getAsJsonObject("pack");
            if (meta == null || !meta.has("format")) return invalid(sourceName, entry, "pack.format fehlt");
            int format = meta.get("format").getAsInt();
            String name = meta.has("name") ? meta.get("name").getAsString() : sourceName;
            String description = meta.has("description") ? meta.get("description").getAsString() : "";
            String error = format == 1 ? null : "Nicht unterstuetztes Pack-Format: " + format;
            return new ResourcePack(sourceName, name, description, format, entry, source, error);
        } catch (Exception e) {
            return invalid(sourceName, entry, "Ungueltiges Pack: " + e.getMessage());
        }
    }

    private static ResourcePack invalid(String name, Path path, String error) {
        return new ResourcePack(name, name, "", -1, path, null, error);
    }
}
