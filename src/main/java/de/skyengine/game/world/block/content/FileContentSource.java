package de.skyengine.game.world.block.content;

import java.io.File;

/** Dateibasierte Inhaltsquelle: ein Wurzelordner mit blocks/, models/ und items/. */
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
    @Override public File items() { return new File(root, "items"); }
    @Override public File recipes() { return new File(root, "recipes"); }
    @Override public File itemTags() { return new File(root, "tags/items"); }
    @Override public File fuels() { return new File(root, "fuels"); }
    @Override public File creativeTabs() { return new File(root, "creative_tabs.json"); }
    @Override public File blockLootTables() { return new File(root, "loot_table/blocks"); }
}
