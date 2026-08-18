package de.skyengine.core.resource;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class ResourceManagerTest {
    @TempDir Path temp;

    @Test
    void selectedPacksOverridePerResourceInTopFirstOrder() throws Exception {
        Path defaults = this.temp.resolve("defaults");
        Path ores = this.temp.resolve("ores");
        Path sand = this.temp.resolve("sand");
        write(defaults.resolve("textures/block/iron.png"), "default-iron");
        write(defaults.resolve("textures/block/sand.png"), "default-sand");
        write(defaults.resolve("textures/block/stone.png"), "default-stone");
        write(ores.resolve("assets/skyengine/textures/block/iron.png"), "ores-iron");
        write(sand.resolve("assets/skyengine/textures/block/sand.png"), "top-sand");

        ResourceManager manager = new ResourceManager(
                new DirectoryResourceSource("defaults", defaults, true));
        ResourcePack orePack = pack("ores", ores);
        ResourcePack sandPack = pack("sand", sand);
        manager.setPacks(List.of(sandPack, orePack));

        assertEquals("ores-iron", read(manager, "game/textures/block/iron.png"));
        assertEquals("top-sand", read(manager, "skyengine:textures/block/sand.png"));
        assertEquals("default-stone", read(manager, "game/textures/block/stone.png"));
        assertEquals(List.of("sand", "ores"), manager.activePackNames());
        assertEquals(3, manager.listResolved("textures/block/").size());
    }

    @Test
    void stackMergeRunsFromDefaultsToHighestPriority() throws Exception {
        Path defaults = this.temp.resolve("defaults");
        Path low = this.temp.resolve("low");
        Path high = this.temp.resolve("high");
        write(defaults.resolve("lang/en_us.json"), "default");
        write(low.resolve("assets/skyengine/lang/en_us.json"), "low");
        write(high.resolve("assets/skyengine/lang/en_us.json"), "high");
        ResourceManager manager = new ResourceManager(
                new DirectoryResourceSource("defaults", defaults, true));
        manager.setPacks(List.of(pack("high", high), pack("low", low)));

        List<String> values = manager.findStack(ResourceId.of("game/lang/en_us.json")).stream()
                .map(match -> readUnchecked(match)).toList();
        assertEquals(List.of("default", "low", "high"), values);
    }

    @Test
    void zipSourceReadsAssetsAndHidesUnsafeEntries() throws Exception {
        Path zip = this.temp.resolve("pack.zip");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip))) {
            zip(out, "assets/skyengine/textures/block/ore.png", "zip-ore");
            zip(out, "assets/skyengine/../secret.txt", "secret");
        }
        ZipResourceSource source = new ZipResourceSource("zip", zip);
        ResourceId ore = ResourceId.of("skyengine:textures/block/ore.png");

        assertTrue(source.contains(ore));
        try (var in = source.open(ore)) {
            assertEquals("zip-ore", new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
        assertEquals(1, source.list("").size());
    }

    private static ResourcePack pack(String name, Path root) {
        return new ResourcePack(name, name, "", 1, root,
                new DirectoryResourceSource(name, root, false), null);
    }

    private static void write(Path file, String value) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, value, StandardCharsets.UTF_8);
    }

    private static void zip(ZipOutputStream out, String name, String value) throws IOException {
        out.putNextEntry(new ZipEntry(name));
        out.write(value.getBytes(StandardCharsets.UTF_8));
        out.closeEntry();
    }

    private static String read(ResourceManager manager, String id) throws IOException {
        try (var in = manager.open(id)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String readUnchecked(ResourceManager.Match match) {
        try (var in = match.open()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }
}
