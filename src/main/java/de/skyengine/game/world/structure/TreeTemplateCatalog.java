package de.skyengine.game.world.structure;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/** Versionierter, globaler Katalog fuer native Baumvarianten. */
public final class TreeTemplateCatalog {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String[] TYPES = {"oak", "birch", "spruce", "acacia", "jungle", "redwood", "palm"};

    public record Group(String folder, int templateWeight, int proceduralWeight) {
        public Group {
            if (folder == null || folder.isBlank() || folder.startsWith("/") || folder.contains("..")) {
                throw new IllegalArgumentException("Ungueltiger Baum-Structure-Ordner: " + folder);
            }
            if (templateWeight < 0 || proceduralWeight < 0 || templateWeight + proceduralWeight <= 0) {
                throw new IllegalArgumentException("Baumgewichte muessen nichtnegativ und zusammen > 0 sein");
            }
        }
    }

    private static final class FileData {
        int version = 1;
        Map<String, Group> groups = new LinkedHashMap<>();
    }

    private final Map<String, Group> groups;
    private final int fingerprint;

    private TreeTemplateCatalog(Map<String, Group> groups) {
        this.groups = Map.copyOf(groups);
        this.fingerprint = this.groups.hashCode();
    }

    public Group group(String type) { return groups.get(type); }
    public int fingerprint() { return fingerprint; }

    public static TreeTemplateCatalog load(Path path) throws IOException {
        if (!Files.isRegularFile(path)) {
            Files.createDirectories(path.getParent());
            FileData defaults = defaults();
            Files.writeString(path, GSON.toJson(defaults), StandardCharsets.UTF_8);
        }
        FileData data;
        try (var reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            data = GSON.fromJson(reader, FileData.class);
        }
        if (data == null || data.version != 1 || data.groups == null) {
            throw new IOException("Ungueltiger tree_templates.json-Katalog");
        }
        LinkedHashMap<String, Group> groups = new LinkedHashMap<>();
        for (String type : TYPES) {
            Group group = data.groups.get(type);
            if (group == null) throw new IOException("Baumgruppe fehlt: " + type);
            try { groups.put(type, new Group(group.folder(), group.templateWeight(), group.proceduralWeight())); }
            catch (IllegalArgumentException e) { throw new IOException("Baumgruppe " + type + ": " + e.getMessage(), e); }
        }
        return new TreeTemplateCatalog(groups);
    }

    private static FileData defaults() {
        FileData data = new FileData();
        for (String type : TYPES) data.groups.put(type, new Group("trees/" + type + "/", 1, 7));
        return data;
    }
}
