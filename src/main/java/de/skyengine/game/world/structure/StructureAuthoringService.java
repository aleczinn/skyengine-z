package de.skyengine.game.world.structure;

import de.skyengine.game.world.Dimension;
import de.skyengine.game.world.block.Identifier;

import java.io.IOException;
import java.util.List;

/** Weltbezogene Authoring-Sitzung; API fuer Commands und einen spaeteren Structure Block. */
public final class StructureAuthoringService {
    private final StructureTemplateManager templates;
    private final StructureTemplateBuilder builder = new StructureTemplateBuilder();
    private final StructurePlacement placement = new StructurePlacement();
    private StructureSelection selection;
    private StructureTemplate loaded;
    private StructureBounds lastPreview;
    private Identifier previewDimension;

    public StructureAuthoringService(StructureTemplateManager templates) {
        this.templates = templates;
    }

    public StructureSelection selection() { return selection; }
    public StructureTemplate loaded() { return loaded; }
    public StructureBounds previewBounds() { return lastPreview; }
    public Identifier previewDimension() { return previewDimension; }
    public StructureTemplateManager templates() { return templates; }

    public void pos1(Identifier dimension, int x, int y, int z) {
        if (selection == null || !dimension.equals(selection.dimension())) selection = new StructureSelection(dimension);
        selection = selection.withPos1(x, y, z);
    }

    public void pos2(Identifier dimension, int x, int y, int z) {
        if (selection == null || !dimension.equals(selection.dimension())) selection = new StructureSelection(dimension);
        selection = selection.withPos2(x, y, z);
    }

    public void anchor(Identifier dimension, int x, int y, int z) {
        if (selection == null || !dimension.equals(selection.dimension())) {
            throw new IllegalStateException("Keine vollstaendige Auswahl in dieser Dimension");
        }
        selection = selection.withAnchor(x, y, z);
    }

    public void resetAnchor(Identifier dimension) {
        if (selection == null || !dimension.equals(selection.dimension())) {
            throw new IllegalStateException("Keine Auswahl in dieser Dimension");
        }
        selection = selection.resetAnchor();
    }

    public StructureTemplate save(Dimension dimension, Identifier id, boolean includeAir,
                                  boolean overwrite) throws IOException {
        StructureTemplate template = builder.capture(dimension, selection, id, includeAir);
        templates.saveAuthored(template, overwrite);
        loaded = template;
        return template;
    }

    public StructureTemplate save(Dimension dimension, String reference, boolean includeAir,
                                  boolean overwrite) throws IOException {
        return save(dimension, templates.idForNewReference(reference), includeAir, overwrite);
    }

    public StructureTemplate load(Identifier id) throws IOException {
        loaded = templates.get(id);
        if (loaded == null) throw new IOException("Structure nicht gefunden: " + id);
        return loaded;
    }

    public StructureTemplate load(String reference) throws IOException {
        loaded = templates.get(reference);
        if (loaded == null) throw new IOException("Structure nicht gefunden: " + reference);
        return loaded;
    }

    public StructurePlacement.Result paste(Dimension dimension, int x, int y, int z,
                                           StructureTransform transform, StructurePlacement.Rule rule) {
        if (loaded == null) throw new IllegalStateException("Keine Structure geladen");
        previewAt(dimension.getDimensionId(), x, y, z, transform);
        return placement.placeInWorld(loaded, dimension, x, y, z, transform, rule);
    }

    /** Aktualisiert nur die Bounding-Box; LOAD-GUIs und Commands koennen damit vorab anzeigen. */
    public void previewAt(Identifier dimension, int x, int y, int z, StructureTransform transform) {
        if (loaded == null) throw new IllegalStateException("Keine Structure geladen");
        int[] xs = {0, loaded.sizeX() - 1, 0, loaded.sizeX() - 1};
        int[] zs = {0, 0, loaded.sizeZ() - 1, loaded.sizeZ() - 1};
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
        for (int i = 0; i < 4; i++) {
            int tx = x + transform.transformedX(xs[i] - loaded.anchorX(), zs[i] - loaded.anchorZ());
            int tz = z + transform.transformedZ(xs[i] - loaded.anchorX(), zs[i] - loaded.anchorZ());
            minX = Math.min(minX, tx); maxX = Math.max(maxX, tx);
            minZ = Math.min(minZ, tz); maxZ = Math.max(maxZ, tz);
        }
        lastPreview = new StructureBounds(minX, y - loaded.anchorY(), minZ, maxX,
                y - loaded.anchorY() + loaded.sizeY() - 1, maxZ);
        previewDimension = dimension;
    }
}
