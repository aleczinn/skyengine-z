package de.skyengine.graphics.shaderpack;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** On-disk contract of a native SkyEngine shader pack. */
public final class ShaderPackManifest {
    public int schema = 1;
    public String id;
    public String name;
    public Map<String, String> programs = new LinkedHashMap<>();
    public Map<String, String> textures = new LinkedHashMap<>();
    public List<String> post = List.of("bloom", "color_grading");

    void validate(String directoryName) {
        if (this.schema != 1) throw new IllegalArgumentException("Unsupported shader-pack schema: " + this.schema);
        if (this.id == null || !this.id.matches("[a-z0-9_.-]+")) {
            throw new IllegalArgumentException("Invalid shader-pack id: " + this.id);
        }
        if (directoryName != null && !this.id.equals(directoryName)) {
            throw new IllegalArgumentException("Shader-pack id does not match directory: " + this.id);
        }
        if (this.name == null || this.name.isBlank()) this.name = this.id;
        if (this.programs == null) this.programs = new LinkedHashMap<>();
        if (this.textures == null) this.textures = new LinkedHashMap<>();
        if (this.post == null) this.post = List.of();
        for (Map.Entry<String, String> entry : this.programs.entrySet()) validateRelative(entry.getValue());
        for (Map.Entry<String, String> entry : this.textures.entrySet()) validateRelative(entry.getValue());
    }

    static void validateRelative(String path) {
        if (path == null || path.isBlank() || path.indexOf('\\') >= 0 || path.startsWith("/")
                || path.matches("^[A-Za-z]:.*")) {
            throw new IllegalArgumentException("Invalid shader-pack path: " + path);
        }
        for (String part : path.split("/")) {
            if (part.equals("..") || part.equals(".")) {
                throw new IllegalArgumentException("Shader-pack path escapes its root: " + path);
            }
        }
    }
}
