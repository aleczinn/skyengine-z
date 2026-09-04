package de.skyengine.game.world.block;

import de.skyengine.shared.EngineInfo;

import java.util.Locale;

public record Identifier(String namespace, String path) {

    /** Default fuer Spielinhalte; bewusst getrennt von ResourceId.DEFAULT_NAMESPACE. */
    public static final String DEFAULT_NAMESPACE = EngineInfo.CONTENT_NAMESPACE;

    public Identifier {
        if (namespace == null || namespace.isBlank()) namespace = DEFAULT_NAMESPACE;
        namespace = namespace.toLowerCase(Locale.ROOT);
        if (!namespace.matches("[a-z0-9_.-]+")) {
            throw new IllegalArgumentException("Ungueltiger Namespace: " + namespace);
        }
        if (path == null || path.isBlank() || !path.matches("[a-z0-9._/-]+")
                || path.startsWith("/") || path.endsWith("/") || path.contains("//")
                || path.contains("..")) {
            throw new IllegalArgumentException("Ungueltiger Identifier-Pfad: " + path);
        }
    }

    public static Identifier of(String id) {
        return of(id, DEFAULT_NAMESPACE);
    }

    /** Qualifiziert unbenannte IDs mit dem Namespace ihrer Content-Quelle. */
    public static Identifier of(String id, String defaultNamespace) {
        if (id == null) throw new IllegalArgumentException("Identifier fehlt");
        id = id.trim().toLowerCase(Locale.ROOT);
        int i = id.indexOf(':');
        if (i == -1) return new Identifier(defaultNamespace, id);
        if (i != id.lastIndexOf(':')) throw new IllegalArgumentException("Ungueltiger Identifier: " + id);
        return new Identifier(id.substring(0, i), id.substring(i + 1));
    }

    @Override
    public String toString() {
        return this.namespace + ":" + this.path;
    }
}
