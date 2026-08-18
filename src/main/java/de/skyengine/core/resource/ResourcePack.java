package de.skyengine.core.resource;

import java.nio.file.Path;
import java.nio.file.Files;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Ein gefundener Pack-Kandidat, inklusive Validierungsstatus. */
public record ResourcePack(
        String sourceName,
        String displayName,
        String description,
        int format,
        Path path,
        ResourceSource source,
        String error
) {
    public boolean valid() { return this.error == null && this.source != null && this.format == 1; }

    /** Optionales {@code pack.png} am Pack-Root; null bei fehlendem oder unbrauchbarem Bild. */
    public byte[] readIcon() {
        final int maxBytes = 8 * 1024 * 1024;
        try {
            if (Files.isDirectory(this.path)) {
                Path icon = this.path.resolve("pack.png");
                if (!Files.isRegularFile(icon) || Files.size(icon) > maxBytes) return null;
                return Files.readAllBytes(icon);
            }
            try (ZipFile zip = new ZipFile(this.path.toFile())) {
                ZipEntry icon = zip.getEntry("pack.png");
                if (icon == null || icon.isDirectory() || icon.getSize() > maxBytes) return null;
                try (var in = zip.getInputStream(icon)) {
                    byte[] bytes = in.readNBytes(maxBytes + 1);
                    return bytes.length <= maxBytes ? bytes : null;
                }
            }
        } catch (Exception ignored) {
            return null;
        }
    }
}
