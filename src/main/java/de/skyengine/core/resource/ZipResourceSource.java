package de.skyengine.core.resource;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** ZIP-Pack. Streams werden in Speicher kopiert, sodass kein ZipFile offen gehalten wird. */
public final class ZipResourceSource implements ResourceSource {
    private final String name;
    private final Path zip;

    public ZipResourceSource(String name, Path zip) {
        this.name = name;
        this.zip = zip.toAbsolutePath().normalize();
    }

    @Override public String name() { return this.name; }

    private static String entryName(ResourceId id) {
        return id.assetPath();
    }

    @Override
    public boolean contains(ResourceId id) {
        try (ZipFile file = new ZipFile(this.zip.toFile())) {
            ZipEntry entry = file.getEntry(entryName(id));
            return entry != null && !entry.isDirectory() && safe(entry.getName());
        } catch (IOException ignored) {
            return false;
        }
    }

    @Override
    public InputStream open(ResourceId id) throws IOException {
        try (ZipFile file = new ZipFile(this.zip.toFile())) {
            ZipEntry entry = file.getEntry(entryName(id));
            if (entry == null || entry.isDirectory() || !safe(entry.getName())) {
                throw new IOException("Ressource nicht gefunden: " + id + " in " + this.name);
            }
            try (InputStream in = file.getInputStream(entry)) {
                return new ByteArrayInputStream(in.readAllBytes());
            }
        }
    }

    @Override
    public Set<ResourceId> list(String pathPrefix) throws IOException {
        String prefix = pathPrefix == null ? "" : pathPrefix.replace('\\', '/');
        Set<ResourceId> out = new LinkedHashSet<>();
        try (ZipFile file = new ZipFile(this.zip.toFile())) {
            var entries = file.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                if (entry.isDirectory() || !safe(name) || !name.startsWith("assets/")) continue;
                String rest = name.substring("assets/".length());
                int slash = rest.indexOf('/');
                if (slash <= 0 || slash == rest.length() - 1) continue;
                String path = rest.substring(slash + 1);
                if (!path.startsWith(prefix)) continue;
                try {
                    out.add(new ResourceId(rest.substring(0, slash), path));
                } catch (IllegalArgumentException ignored) {
                    // Unsichere/ungueltige ZIP-Eintraege werden nie sichtbar.
                }
            }
        }
        return out;
    }

    static boolean safe(String name) {
        if (name == null || name.isBlank() || name.startsWith("/") || name.contains("\\")) return false;
        for (String part : name.split("/")) {
            if (part.equals(".") || part.equals("..") || part.isBlank()) return false;
        }
        return true;
    }
}
