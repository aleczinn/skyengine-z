package de.skyengine.graphics.shaderpack;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;

/** On-disk contract of a native SkyEngine shader pack. */
public final class ShaderPackManifest {
    public int schema = 1;
    public String id;
    public String name;
    public Map<String, String> programs = new LinkedHashMap<>();
    public Map<String, String> textures = new LinkedHashMap<>();
    public List<String> post = List.of("bloom", "color_grading");
    public List<Setting> settings = List.of();
    public Map<String, Resource> resources = new LinkedHashMap<>();
    public List<Pass> passes = List.of();

    /** Vom Pack deklarierter Render-Graph-Target. Die Engine besitzt nur dessen Lebenszeit. */
    public static final class Resource {
        public String format = "rgba16f";
        public double scale = 1.0;
        public int width;
        public int height;
        public String filter = "linear";
        public boolean persistent;
    }

    /** Deklarativer Pass. Geometry-Hooks benennen nur eine Engine-Quelle, nie Engine-Shadercode. */
    public static final class Pass {
        public String id;
        public String type = "fullscreen";
        public String hook = "pre_taa";
        public String program;
        public String vertexProgram;
        public String geometry;
        public Map<String, String> inputs = new LinkedHashMap<>();
        public List<String> outputs = List.of();
        public String condition;
    }

    /** Vom Pack deklarierte Laufzeitoption; der Uniformname macht die GUI shaderagnostisch. */
    public static final class Setting {
        public String key;
        public String label;
        public String uniform;
        public String define;
        public String binding = "uniform";
        public String group = "general";
        public String type = "float";
        public double defaultValue;
        public double min;
        public double max = 1.0;
        public double step = 1.0;
        public List<Double> values = List.of();
        public List<String> options = List.of();
    }

    void validate(String directoryName) {
        if (this.schema != 1 && this.schema != 2) {
            throw new IllegalArgumentException("Unsupported shader-pack schema: " + this.schema);
        }
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
        if (this.resources == null) this.resources = new LinkedHashMap<>();
        if (this.passes == null) this.passes = List.of();
        for (Map.Entry<String, String> entry : this.programs.entrySet()) validateRelative(entry.getValue());
        for (Map.Entry<String, String> entry : this.textures.entrySet()) validateRelative(entry.getValue());
        validateGraph();
        HashSet<String> settingKeys = new HashSet<>();
        for (Setting setting : this.settings) {
            if (setting.key == null || !setting.key.matches("[a-z0-9_.-]+")
                    || !settingKeys.add(setting.key)) {
                throw new IllegalArgumentException("Invalid or duplicate shader setting: " + setting.key);
            }
            if (setting.binding == null) setting.binding = "uniform";
            if (!"uniform".equals(setting.binding) && !"define".equals(setting.binding)) {
                throw new IllegalArgumentException("Unsupported shader setting binding: " + setting.binding);
            }
            if ("uniform".equals(setting.binding)
                    && (setting.uniform == null || !setting.uniform.matches("u_[A-Za-z0-9_]+"))) {
                throw new IllegalArgumentException("Invalid shader setting uniform: " + setting.uniform);
            }
            if ("define".equals(setting.binding)
                    && (setting.define == null || !setting.define.matches("[A-Z][A-Z0-9_]*"))) {
                throw new IllegalArgumentException("Invalid shader setting define: " + setting.define);
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
            if (setting.group == null || setting.group.isBlank()) setting.group = "general";
        }
    }

    private void validateGraph() {
        if (this.schema == 1 && (!this.resources.isEmpty() || !this.passes.isEmpty())) {
            throw new IllegalArgumentException("Render graph resources require shader-pack schema 2");
        }
        Set<String> formats = Set.of("r8", "rg8", "rgba8", "r16f", "rg16f", "rgb16f", "rgba16f",
                "depth16", "depth24", "depth32f");
        for (Map.Entry<String, Resource> entry : this.resources.entrySet()) {
            if (!entry.getKey().matches("[a-z][a-z0-9_.-]*") || entry.getValue() == null) {
                throw new IllegalArgumentException("Invalid shader resource: " + entry.getKey());
            }
            Resource resource = entry.getValue();
            if (!formats.contains(resource.format) || resource.scale <= 0.0 || resource.scale > 1.0
                    || (!"nearest".equals(resource.filter) && !"linear".equals(resource.filter))) {
                throw new IllegalArgumentException("Invalid shader resource declaration: " + entry.getKey());
            }
            if ((resource.width == 0) != (resource.height == 0)
                    || resource.width < 0 || resource.height < 0
                    || resource.width > 8192 || resource.height > 8192) {
                throw new IllegalArgumentException("Invalid fixed shader resource size: " + entry.getKey());
            }
        }
        Set<String> passIds = new HashSet<>();
        for (Pass pass : this.passes) {
            if (pass == null || pass.id == null || !pass.id.matches("[a-z][a-z0-9_.-]*")
                    || !passIds.add(pass.id)) {
                throw new IllegalArgumentException("Invalid or duplicate shader pass: "
                        + (pass == null ? null : pass.id));
            }
            if (!Set.of("geometry", "fullscreen", "copy").contains(pass.type)) {
                throw new IllegalArgumentException("Unsupported shader pass type: " + pass.type);
            }
            if (!Set.of("shadow", "opaque", "translucent", "pre_taa", "post_taa").contains(pass.hook)) {
                throw new IllegalArgumentException("Unsupported shader pass hook: " + pass.hook);
            }
            if (!"copy".equals(pass.type)
                    && (pass.program == null || !this.programs.containsKey(pass.program))) {
                throw new IllegalArgumentException("Unknown program for shader pass " + pass.id + ": "
                        + pass.program);
            }
            if ("geometry".equals(pass.type)) {
                if (pass.vertexProgram == null || !this.programs.containsKey(pass.vertexProgram)) {
                    throw new IllegalArgumentException("Unknown vertex program for geometry pass "
                            + pass.id + ": " + pass.vertexProgram);
                }
                if (pass.geometry == null || pass.geometry.isBlank()) {
                    throw new IllegalArgumentException("Missing geometry source for pass " + pass.id);
                }
            }
            if (pass.inputs == null) pass.inputs = new LinkedHashMap<>();
            if (pass.outputs == null) pass.outputs = List.of();
            Set<String> engineInputs = Set.of("scene_color", "scene_depth", "world_depth");
            for (Map.Entry<String, String> input : pass.inputs.entrySet()) {
                if (input.getKey() == null || !input.getKey().matches("[a-z][a-z0-9_.-]*")
                        || (!engineInputs.contains(input.getValue())
                        && !this.resources.containsKey(input.getValue()))) {
                    throw new IllegalArgumentException("Unknown input resource for shader pass "
                            + pass.id + ": " + input.getValue());
                }
            }
            Set<String> uniqueOutputs = new HashSet<>();
            for (String output : pass.outputs) {
                if (!this.resources.containsKey(output) || !uniqueOutputs.add(output)) {
                    throw new IllegalArgumentException("Unknown output resource for shader pass " + pass.id
                            + ": " + output);
                }
            }
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
