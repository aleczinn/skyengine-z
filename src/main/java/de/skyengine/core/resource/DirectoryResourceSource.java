package de.skyengine.core.resource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Stream;

/** Pack-Quelle aus einem Verzeichnis. */
public final class DirectoryResourceSource implements ResourceSource {
    private final String name;
    private final Path root;
    private final boolean defaultLayout;

    /** @param defaultLayout true, wenn {@code root} direkt dem bisherigen {@code game/}-Ordner entspricht. */
    public DirectoryResourceSource(String name, Path root, boolean defaultLayout) {
        this.name = name;
        this.root = root.toAbsolutePath().normalize();
        this.defaultLayout = defaultLayout;
    }

    @Override public String name() { return this.name; }

    private Path resolve(ResourceId id) {
        if (this.defaultLayout && !id.namespace().equals(ResourceId.DEFAULT_NAMESPACE)) return null;
        Path relative = this.defaultLayout
                ? Path.of(id.path())
                : Path.of("assets", id.namespace()).resolve(id.path());
        Path result = this.root.resolve(relative).normalize();
        return result.startsWith(this.root) ? result : null;
    }

    @Override
    public boolean contains(ResourceId id) {
        Path path = this.resolve(id);
        return path != null && Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS);
    }

    @Override
    public InputStream open(ResourceId id) throws IOException {
        Path path = this.resolve(id);
        if (path == null || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Ressource nicht gefunden: " + id + " in " + this.name);
        }
        return Files.newInputStream(path);
    }

    @Override
    public Set<ResourceId> list(String pathPrefix) throws IOException {
        String prefix = pathPrefix == null ? "" : pathPrefix.replace('\\', '/');
        Set<ResourceId> out = new LinkedHashSet<>();
        if (!Files.isDirectory(this.root)) return out;
        if (this.defaultLayout) {
            Path start = prefix.isEmpty() ? this.root : this.root.resolve(prefix).normalize();
            if (!start.startsWith(this.root) || !Files.exists(start)) return out;
            try (Stream<Path> walk = Files.walk(start)) {
                walk.filter(p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS))
                        .forEach(p -> out.add(new ResourceId(ResourceId.DEFAULT_NAMESPACE,
                                this.root.relativize(p).toString().replace('\\', '/'))));
            }
            return out;
        }
        Path assets = this.root.resolve("assets");
        if (!Files.isDirectory(assets)) return out;
        try (Stream<Path> namespaces = Files.list(assets)) {
            for (Path namespace : namespaces.filter(p -> Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)).toList()) {
                Path start = prefix.isEmpty() ? namespace : namespace.resolve(prefix).normalize();
                if (!start.startsWith(namespace) || !Files.exists(start)) continue;
                try (Stream<Path> walk = Files.walk(start)) {
                    walk.filter(p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS))
                            .forEach(p -> out.add(new ResourceId(namespace.getFileName().toString(),
                                    namespace.relativize(p).toString().replace('\\', '/'))));
                }
            }
        }
        return out;
    }
}
