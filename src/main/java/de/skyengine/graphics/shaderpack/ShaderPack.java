package de.skyengine.graphics.shaderpack;

import de.skyengine.core.file.GameDirectory;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

/** Immutable, validated source view of one built-in or external shader pack. */
public final class ShaderPack {
    private static final String BUILTIN_ROOT = "engine/shaderpacks/";
    private final ShaderPackManifest manifest;
    private final Path externalRoot;

    ShaderPack(ShaderPackManifest manifest, Path externalRoot) {
        this.manifest = manifest;
        this.externalRoot = externalRoot;
    }

    public ShaderPackManifest manifest() {
        return this.manifest;
    }

    public String program(String key) {
        String path = this.manifest.programs.get(key);
        if (path == null) throw new IllegalArgumentException("Shader program is not declared: " + key);
        return preprocess(path, new HashSet<>());
    }

    public InputStream texture(String key) throws IOException {
        String path = this.manifest.textures.get(key);
        if (path == null) throw new IllegalArgumentException("Shader texture is not declared: " + key);
        return open(path);
    }

    private String preprocess(String path, Set<String> stack) {
        ShaderPackManifest.validateRelative(path);
        if (!stack.add(path)) throw new IllegalArgumentException("Shader include cycle: " + stack + " -> " + path);
        String source;
        try (InputStream input = open(path)) {
            source = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalArgumentException("Cannot read shader-pack file: " + path, e);
        }
        StringBuilder result = new StringBuilder(source.length() + 256);
        String parent = path.contains("/") ? path.substring(0, path.lastIndexOf('/') + 1) : "";
        for (String line : source.split("\\R", -1)) {
            String trimmed = line.trim();
            if (trimmed.startsWith("#include \"") && trimmed.endsWith("\"")) {
                String include = trimmed.substring(10, trimmed.length() - 1);
                String resolved = normalize(parent + include);
                result.append(preprocess(resolved, stack));
            } else {
                result.append(line).append('\n');
            }
        }
        stack.remove(path);
        return result.toString();
    }

    private static String normalize(String path) {
        Path normalized = Path.of(path).normalize();
        String value = normalized.toString().replace('\\', '/');
        ShaderPackManifest.validateRelative(value);
        return value;
    }

    private InputStream open(String path) throws IOException {
        ShaderPackManifest.validateRelative(path);
        if (this.externalRoot != null) {
            Path resolved = this.externalRoot.resolve(path).normalize();
            if (!resolved.startsWith(this.externalRoot)) throw new IOException("Path escapes shader pack: " + path);
            return Files.newInputStream(resolved);
        }
        String resource = BUILTIN_ROOT + this.manifest.id + "/" + path;
        InputStream input = ShaderPack.class.getClassLoader().getResourceAsStream(resource);
        if (input == null) throw new IOException("Missing built-in shader resource: " + resource);
        return input;
    }

    static File externalDirectory() {
        return GameDirectory.resolve("shaderpacks");
    }
}
