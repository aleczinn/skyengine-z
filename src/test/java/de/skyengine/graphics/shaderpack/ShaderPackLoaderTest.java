package de.skyengine.graphics.shaderpack;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShaderPackLoaderTest {
    @TempDir Path temporary;

    @Test
    void resolvesRelativeIncludesInsidePack() throws Exception {
        Files.createDirectories(this.temporary.resolve("shaders/include"));
        Files.writeString(this.temporary.resolve("pack.json"), """
                {"schema":1,"id":"test","name":"Test","programs":{"sky":"shaders/main.glsl"},"textures":{},"post":[]}
                """);
        Files.writeString(this.temporary.resolve("shaders/main.glsl"),
                "#version 460 core\n#include \"include/value.glsl\"\nvoid main() {}\n");
        Files.writeString(this.temporary.resolve("shaders/include/value.glsl"), "const int VALUE = 7;\n");

        ShaderPack pack = new ShaderPackLoader().loadExternal(this.temporary, "test");
        assertTrue(pack.program("sky").contains("const int VALUE = 7;"));
    }

    @Test
    void rejectsDirectoryTraversal() throws Exception {
        Files.writeString(this.temporary.resolve("pack.json"), """
                {"schema":1,"id":"test","name":"Test","programs":{"sky":"../escape.glsl"},"textures":{},"post":[]}
                """);
        assertThrows(IllegalArgumentException.class,
                () -> new ShaderPackLoader().loadExternal(this.temporary, "test"));
    }

    @Test
    void builtInPhotonManifestAndSourcesAreComplete() {
        ShaderPack pack = new ShaderPackLoader().load("photon");
        assertEquals("photon", pack.manifest().id);
        for (String key : pack.manifest().programs.keySet()) {
            assertTrue(pack.program(key).startsWith("#version 460 core"), key);
        }
    }

    @Test
    void builtInPhotonDoesNotDeclareReservedInterpolationQualifiers() {
        ShaderPack pack = new ShaderPackLoader().load("photon");
        String declaration = "(?s).*\\b(?:bool|int|uint|float|double|[biud]?vec[234]|mat[234])\\s+"
                + "(?:smooth|flat|noperspective|centroid|sample|patch)\\b.*";
        for (String key : pack.manifest().programs.keySet()) {
            assertFalse(pack.program(key).matches(declaration), key);
        }
    }
}
