package de.skyengine.game.world.block;

public record Identifier(String namespace, String path) {

    public static final String DEFAULT_NAMESPACE = "skyengine";

    public static Identifier of(String id) {
        int i = id.indexOf(':');
        if (i == -1) return new Identifier(DEFAULT_NAMESPACE, id);
        return new Identifier(id.substring(0, i), id.substring(i + 1));
    }

    @Override
    public String toString() {
        return this.namespace + ":" + this.path;
    }
}