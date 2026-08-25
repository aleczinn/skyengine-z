package de.skyengine.game.world.structure;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Installiert mitgelieferte Startvorlagen einmalig in den beschreibbaren globalen Katalog. */
final class DefaultStructureInstaller {
    private static final String MANIFEST = "/game/worldgen/default-structures.txt";
    private static final String RESOURCE_ROOT = "/game/worldgen/structures/";
    private static final String MARKER = ".default-structures-v1";

    static void install(Path targetRoot) throws IOException {
        Path root = targetRoot.toAbsolutePath().normalize();
        Path marker = root.resolve(MARKER);
        if (Files.isRegularFile(marker)) return;

        Files.createDirectories(root);
        for (String relative : manifest()) {
            Path target = root.resolve(relative).normalize();
            if (!target.startsWith(root)) throw new IOException("Unsicherer Default-Structure-Pfad: " + relative);
            if (Files.exists(target)) continue;
            Files.createDirectories(target.getParent());
            try (InputStream in = DefaultStructureInstaller.class.getResourceAsStream(RESOURCE_ROOT + relative)) {
                if (in == null) throw new IOException("Default-Structure fehlt: " + relative);
                Files.copy(in, target);
            }
        }
        Files.writeString(marker, "version=1\n", StandardCharsets.UTF_8);
    }

    private static List<String> manifest() throws IOException {
        InputStream stream = DefaultStructureInstaller.class.getResourceAsStream(MANIFEST);
        if (stream == null) throw new IOException("Default-Structure-Manifest fehlt: " + MANIFEST);
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            return reader.lines().map(String::trim)
                    .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                    .peek(line -> {
                        if (!line.endsWith(".structure") || line.startsWith("/") || line.contains("..")) {
                            throw new IllegalArgumentException("Ungueltiger Default-Structure-Pfad: " + line);
                        }
                    }).toList();
        } catch (IllegalArgumentException e) {
            throw new IOException(e.getMessage(), e);
        }
    }

    private DefaultStructureInstaller() {}
}
