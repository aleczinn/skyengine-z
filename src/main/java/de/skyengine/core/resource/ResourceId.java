package de.skyengine.core.resource;

import java.util.Locale;
import java.util.Objects;

/**
 * Normalisierte, logische Ressourcen-ID. Das Pack-Layout ist
 * {@code assets/<namespace>/<path>}; bestehende Engine-Pfade unter {@code game/}
 * werden dem Namespace {@code skyengine} zugeordnet.
 */
public record ResourceId(String namespace, String path) {

    public static final String DEFAULT_NAMESPACE = "skyengine";

    public ResourceId {
        namespace = normalizeNamespace(namespace);
        path = normalizePath(path);
    }

    public static ResourceId of(String value) {
        Objects.requireNonNull(value, "value");
        String normalized = value.replace('\\', '/');
        if (normalized.startsWith("game/")) {
            return new ResourceId(DEFAULT_NAMESPACE, normalized.substring("game/".length()));
        }
        if (normalized.startsWith("assets/")) {
            String rest = normalized.substring("assets/".length());
            int slash = rest.indexOf('/');
            if (slash <= 0 || slash == rest.length() - 1) {
                throw new IllegalArgumentException("Ungueltiger assets-Pfad: " + value);
            }
            return new ResourceId(rest.substring(0, slash), rest.substring(slash + 1));
        }
        int colon = normalized.indexOf(':');
        if (colon >= 0) {
            return new ResourceId(normalized.substring(0, colon), normalized.substring(colon + 1));
        }
        return new ResourceId(DEFAULT_NAMESPACE, normalized);
    }

    public String assetPath() {
        return "assets/" + this.namespace + "/" + this.path;
    }

    public String legacyPath() {
        return this.namespace.equals(DEFAULT_NAMESPACE) ? "game/" + this.path : this.assetPath();
    }

    private static String normalizeNamespace(String namespace) {
        if (namespace == null || namespace.isBlank()) throw new IllegalArgumentException("Namespace fehlt");
        String value = namespace.toLowerCase(Locale.ROOT);
        if (!value.matches("[a-z0-9_.-]+")) {
            throw new IllegalArgumentException("Ungueltiger Namespace: " + namespace);
        }
        return value;
    }

    private static String normalizePath(String path) {
        if (path == null || path.isBlank()) throw new IllegalArgumentException("Ressourcenpfad fehlt");
        String value = path.replace('\\', '/');
        if (value.startsWith("/") || value.endsWith("/") || value.contains("//")) {
            throw new IllegalArgumentException("Ungueltiger Ressourcenpfad: " + path);
        }
        for (String part : value.split("/")) {
            if (part.isBlank() || part.equals(".") || part.equals("..")) {
                throw new IllegalArgumentException("Unsicherer Ressourcenpfad: " + path);
            }
        }
        return value;
    }
}
