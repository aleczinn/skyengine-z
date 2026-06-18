package de.skyengine.game.world.block.content;

import java.io.File;

/** Dateibasierte Inhaltsquelle: ein Wurzelordner mit blocks/ und models/. */
public final class FileContentSource implements ContentSource {

    private final String namespace;
    private final File root;

    public FileContentSource(String namespace, File root) {
        this.namespace = namespace;
        this.root = root;
    }

    @Override public String namespace() { return namespace; }
    @Override public File blocks() { return new File(root, "blocks"); }
    @Override public File models() { return new File(root, "models"); }
}
