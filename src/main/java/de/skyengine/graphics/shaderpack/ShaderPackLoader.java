package de.skyengine.graphics.shaderpack;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Parses packs without touching OpenGL; kept separate so validation is unit-testable. */
public final class ShaderPackLoader {
    private static final Gson GSON = new GsonBuilder().create();

    public ShaderPack load(String id) {
        if (id == null || !id.matches("[a-z0-9_.-]+")) throw new IllegalArgumentException("Invalid shader-pack id: " + id);
        Path external = ShaderPack.externalDirectory().toPath().resolve(id).normalize();
        if (Files.isDirectory(external)) return loadExternal(external, id);
        return loadBuiltin(id);
    }

    ShaderPack loadExternal(Path root, String id) {
        try (InputStream input = Files.newInputStream(root.resolve("pack.json"))) {
            ShaderPackManifest manifest = parse(input, id);
            ShaderPack pack = new ShaderPack(manifest, root.toAbsolutePath().normalize());
            validateDeclaredSources(pack);
            return pack;
        } catch (IOException e) {
            throw new IllegalArgumentException("Cannot load shader pack " + id, e);
        }
    }

    private ShaderPack loadBuiltin(String id) {
        String resource = "engine/shaderpacks/" + id + "/pack.json";
        try (InputStream input = ShaderPackLoader.class.getClassLoader().getResourceAsStream(resource)) {
            if (input == null) throw new IllegalArgumentException("Unknown shader pack: " + id);
            ShaderPack pack = new ShaderPack(parse(input, null), null);
            validateDeclaredSources(pack);
            return pack;
        } catch (IOException e) {
            throw new IllegalArgumentException("Cannot load built-in shader pack " + id, e);
        }
    }

    private static ShaderPackManifest parse(InputStream input, String directoryName) {
        ShaderPackManifest manifest = GSON.fromJson(new InputStreamReader(input, StandardCharsets.UTF_8), ShaderPackManifest.class);
        if (manifest == null) throw new IllegalArgumentException("Empty shader-pack manifest");
        manifest.validate(directoryName);
        return manifest;
    }

    private static void validateDeclaredSources(ShaderPack pack) {
        for (String key : pack.manifest().programs.keySet()) pack.program(key);
    }

    public ShaderPackLoader() {}
}
