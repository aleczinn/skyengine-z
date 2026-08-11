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
    void builtInVibrantVisualsManifestAndSourcesAreComplete() {
        ShaderPack pack = new ShaderPackLoader().load("vibrant_visuals");
        assertEquals("vibrant_visuals", pack.manifest().id);
        assertEquals(2, pack.manifest().schema);
        for (String key : pack.manifest().programs.keySet()) {
            assertTrue(pack.program(key).startsWith("#version 460 core"), key);
        }
    }

    @Test
    void builtInVibrantVisualsDoesNotDeclareReservedInterpolationQualifiers() {
        ShaderPack pack = new ShaderPackLoader().load("vibrant_visuals");
        String declaration = "(?s).*\\b(?:bool|int|uint|float|double|[biud]?vec[234]|mat[234])\\s+"
                + "(?:smooth|flat|noperspective|centroid|sample|patch)\\b.*";
        for (String key : pack.manifest().programs.keySet()) {
            assertFalse(pack.program(key).matches(declaration), key);
        }
    }

    @Test
    void rejectsGraphPassWithUnknownOutput() throws Exception {
        Files.writeString(this.temporary.resolve("pack.json"), """
                {"schema":2,"id":"test","programs":{"vl":"shader.glsl"},
                 "resources":{},"passes":[{"id":"vl","type":"fullscreen","hook":"pre_taa",
                 "program":"vl","outputs":["missing"]}]}
                """);
        Files.writeString(this.temporary.resolve("shader.glsl"), "#version 460 core\n");
        assertThrows(IllegalArgumentException.class,
                () -> new ShaderPackLoader().loadExternal(this.temporary, "test"));
    }

    @Test
    void rejectsGraphPassWithUnknownInput() throws Exception {
        Files.writeString(this.temporary.resolve("pack.json"), """
                {"schema":2,"id":"test","programs":{"vl":"shader.glsl"},
                 "resources":{},"passes":[{"id":"vl","type":"fullscreen","hook":"pre_taa",
                 "program":"vl","inputs":{"source":"missing"}}]}
                """);
        Files.writeString(this.temporary.resolve("shader.glsl"), "#version 460 core\n");
        assertThrows(IllegalArgumentException.class,
                () -> new ShaderPackLoader().loadExternal(this.temporary, "test"));
    }

    @Test
    void rejectsIncompleteFixedResourceSize() throws Exception {
        Files.writeString(this.temporary.resolve("pack.json"), """
                {"schema":2,"id":"test","programs":{},
                 "resources":{"shadow":{"format":"depth32f","width":2048}},"passes":[]}
                """);
        assertThrows(IllegalArgumentException.class,
                () -> new ShaderPackLoader().loadExternal(this.temporary, "test"));
    }
}
