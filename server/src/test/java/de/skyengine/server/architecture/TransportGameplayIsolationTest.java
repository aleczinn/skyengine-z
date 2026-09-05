package de.skyengine.server.architecture;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Guards the semantic boundary: transport topology must never select gameplay rules. */
class TransportGameplayIsolationTest {
    @Test
    void productiveGameplayDoesNotBranchOnTransportOrDeploymentMode() throws IOException {
        Path root = repositoryRoot(Path.of("").toAbsolutePath());
        List<Path> roots = List.of(
                root.resolve("src/main/java/de/skyengine/game/entity"),
                root.resolve("src/main/java/de/skyengine/game/physics"),
                root.resolve("src/main/java/de/skyengine/game/world/PlayerBlockActions.java"),
                root.resolve("server/src/main/java/de/skyengine/server/world/AuthoritativeWorldRuntime.java"));
        List<String> forbidden = List.of(
                "isIntegrated(", "isDedicated(", "isMultiplayer(",
                "instanceof LocalTransport", "instanceof TCPTransport",
                "instanceof NettyTransport", "getServerChunk(");
        StringBuilder violations = new StringBuilder();
        for (Path sourceRoot : roots) {
            if (Files.isDirectory(sourceRoot)) {
                try (var files = Files.walk(sourceRoot)) {
                    files.filter(path -> path.toString().endsWith(".java"))
                            .forEach(path -> inspect(root, path, forbidden, violations));
                }
            } else {
                inspect(root, sourceRoot, forbidden, violations);
            }
        }
        assertTrue(violations.isEmpty(), () -> "Transport-specific gameplay branch(es):\n" + violations);
    }

    private static void inspect(Path root, Path path, List<String> forbidden, StringBuilder violations) {
        try {
            String source = Files.readString(path);
            for (String token : forbidden) {
                if (source.contains(token)) {
                    violations.append(root.relativize(path)).append(": ").append(token).append('\n');
                }
            }
        } catch (IOException failure) {
            throw new java.io.UncheckedIOException(failure);
        }
    }

    private static Path repositoryRoot(Path start) {
        for (Path candidate = start; candidate != null; candidate = candidate.getParent()) {
            if (Files.isRegularFile(candidate.resolve("settings.gradle.kts"))) return candidate;
        }
        throw new IllegalStateException("Could not locate repository root from " + start);
    }
}
