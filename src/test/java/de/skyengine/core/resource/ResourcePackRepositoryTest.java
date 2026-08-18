package de.skyengine.core.resource;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class ResourcePackRepositoryTest {
    @TempDir Path temp;

    @Test
    void discoversFolderAndZipAndReportsInvalidManifest() throws Exception {
        Path folder = this.temp.resolve("01-folder");
        Files.createDirectories(folder);
        Files.writeString(folder.resolve("pack.json"), manifest("Folder Pack", 1), StandardCharsets.UTF_8);

        Path zip = this.temp.resolve("02-zip.zip");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip))) {
            add(out, "pack.json", manifest("Zip Pack", 1));
            add(out, "pack.png", "icon-bytes");
            add(out, "assets/skyengine/lang/en_us.json", "{}");
        }

        Path invalid = this.temp.resolve("03-invalid");
        Files.createDirectories(invalid);
        Files.writeString(invalid.resolve("pack.json"), manifest("Future Pack", 99), StandardCharsets.UTF_8);

        ResourcePackRepository repository = new ResourcePackRepository(this.temp);
        var packs = repository.refresh();

        assertEquals(3, packs.size());
        assertEquals("Folder Pack", packs.get(0).displayName());
        assertTrue(repository.get("01-folder").valid());
        assertTrue(repository.get("02-zip.zip").valid());
        assertArrayEquals("icon-bytes".getBytes(StandardCharsets.UTF_8),
                repository.get("02-zip.zip").readIcon());
        assertFalse(repository.get("03-invalid").valid());
        assertTrue(repository.get("03-invalid").error().contains("99"));
    }

    private static String manifest(String name, int format) {
        return "{\"pack\":{\"format\":" + format + ",\"name\":\"" + name
                + "\",\"description\":\"test\"}}";
    }

    private static void add(ZipOutputStream out, String name, String value) throws IOException {
        out.putNextEntry(new ZipEntry(name));
        out.write(value.getBytes(StandardCharsets.UTF_8));
        out.closeEntry();
    }
}
