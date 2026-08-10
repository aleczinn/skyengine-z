package de.skyengine.graphics.shaderpack;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.HashSet;

/** On-disk contract of a native SkyEngine shader pack. */
public final class ShaderPackManifest {
    public int schema = 1;
    public String id;
    public String name;
    public Map<String, String> programs = new LinkedHashMap<>();
    public Map<String, String> textures = new LinkedHashMap<>();
    public List<String> post = List.of("bloom", "color_grading");
    public List<Setting> settings = List.of();

    /** Vom Pack deklarierte Laufzeitoption; der Uniformname macht die GUI shaderagnostisch. */
    public static final class Setting {
        public String key;
        public String label;
        public String uniform;
        public String type = "float";
        public double defaultValue;
        public double min;
        public double max = 1.0;
        public double step = 1.0;
        public List<Double> values = List.of();
        public List<String> options = List.of();
    }

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
        if (this.settings == null) this.settings = List.of();
        for (Map.Entry<String, String> entry : this.programs.entrySet()) validateRelative(entry.getValue());
        for (Map.Entry<String, String> entry : this.textures.entrySet()) validateRelative(entry.getValue());
        HashSet<String> settingKeys = new HashSet<>();
        for (Setting setting : this.settings) {
            if (setting.key == null || !setting.key.matches("[a-z0-9_.-]+")
                    || !settingKeys.add(setting.key)) {
                throw new IllegalArgumentException("Invalid or duplicate shader setting: " + setting.key);
            }
            if (setting.uniform == null || !setting.uniform.matches("u_[A-Za-z0-9_]+")) {
                throw new IllegalArgumentException("Invalid shader setting uniform: " + setting.uniform);
            }
            if (!"float".equals(setting.type) && !"boolean".equals(setting.type)
                    && !"choice".equals(setting.type)) {
                throw new IllegalArgumentException("Unsupported shader setting type: " + setting.type);
            }
            if (setting.values == null) setting.values = List.of();
            if (setting.options == null) setting.options = List.of();
            if ("choice".equals(setting.type)
                    && (setting.values.isEmpty() || setting.values.size() != setting.options.size())) {
                throw new IllegalArgumentException("Invalid choices for shader setting: " + setting.key);
            }
            if (!Double.isFinite(setting.min) || !Double.isFinite(setting.max)
                    || !Double.isFinite(setting.step) || setting.max < setting.min || setting.step <= 0.0) {
                throw new IllegalArgumentException("Invalid range for shader setting: " + setting.key);
            }
            setting.defaultValue = Math.clamp(setting.defaultValue, setting.min, setting.max);
            if (setting.label == null || setting.label.isBlank()) setting.label = setting.key;
        }
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
