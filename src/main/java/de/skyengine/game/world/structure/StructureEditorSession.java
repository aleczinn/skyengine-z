package de.skyengine.game.world.structure;

import de.skyengine.game.world.Dimension;
import de.skyengine.game.world.block.BlockPos;
import de.skyengine.game.world.block.Blocks;
import de.skyengine.game.world.block.Identifier;
import de.skyengine.game.world.block.entity.BlockEntity;
import de.skyengine.game.world.block.entity.DataTag;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;

/** UUID-gebundener, nicht persistierter Zustand des In-Engine-Structure-Editors. */
public final class StructureEditorSession {
    public static final int MAX_HISTORY = 20;
    public static final int MAX_HISTORY_CELLS = 1_000_000;
    public static final int MAX_PREVIEW_CELLS = 500_000;

    public record Preview(Identifier dimension, StructureTemplate template, int x, int y, int z,
                          StructureTransform transform, StructurePlacement.Rule rule,
                          StructureBounds bounds) {}
    public record HistoryResult(int operations, int cells) {}

    private final StructureTemplateManager templates;
    private final StructureTemplateBuilder builder = new StructureTemplateBuilder();
    private final StructurePlacement placement = new StructurePlacement();
    private final Map<Identifier, History> histories = new HashMap<>();
    private StructureSelection selection;
    private StructureTemplate loaded;
    private StructureTransform transform = StructureTransform.IDENTITY;
    private Preview preview;

    StructureEditorSession(StructureTemplateManager templates) {
        this.templates = templates;
    }

    public StructureSelection selection() { return selection; }
    public StructureTemplate loaded() { return loaded; }
    public StructureTransform transform() { return transform; }
    public Preview preview() { return preview; }
    public StructureTemplateManager templates() { return templates; }

    /** Entfernt nur die aktuelle WorldEdit-Auswahl; Clipboard und Vorschau bleiben erhalten. */
    public void clearSelection() { this.selection = null; }

    public void pos1(Identifier dimension, int x, int y, int z) {
        if (selection == null || !dimension.equals(selection.dimension())) selection = new StructureSelection(dimension);
        selection = selection.withPos1(x, y, z);
    }

    public void pos2(Identifier dimension, int x, int y, int z) {
        if (selection == null || !dimension.equals(selection.dimension())) selection = new StructureSelection(dimension);
        selection = selection.withPos2(x, y, z);
    }

    public void anchor(Identifier dimension, int x, int y, int z) {
        requireSelection(dimension);
        selection = selection.withAnchor(x, y, z);
    }

    public void resetAnchor(Identifier dimension) {
        requireSelection(dimension);
        selection = selection.resetAnchor();
    }

    private void requireSelection(Identifier dimension) {
        if (selection == null || !dimension.equals(selection.dimension())) {
            throw new IllegalStateException("Keine Auswahl in dieser Dimension");
        }
    }

    public StructureTemplate save(Dimension dimension, String reference, boolean includeAir,
                                  boolean overwrite) throws IOException {
        StructureTemplate template = builder.capture(dimension, selection,
                templates.idForNewReference(reference), includeAir);
        templates.saveAuthored(template, overwrite);
        this.loaded = template;
        this.transform = StructureTransform.IDENTITY;
        this.preview = null;
        return template;
    }

    public StructureTemplate load(String reference) throws IOException {
        StructureTemplate template = templates.get(reference);
        if (template == null) throw new IOException("Structure nicht gefunden: " + reference);
        this.loaded = template;
        this.transform = StructureTransform.IDENTITY;
        this.preview = null;
        return template;
    }

    public StructureTransform rotate(int degrees) {
        if (degrees % 90 != 0) throw new IllegalArgumentException(
                "Rotation muss ein Vielfaches von 90 Grad sein; freie Winkel passen nicht ins Voxelraster");
        int turns = Math.floorMod(degrees / 90, 4);
        this.transform = this.transform.then(new StructureTransform(
                StructureTransform.Rotation.values()[turns], StructureTransform.Mirror.NONE));
        refreshPreview();
        return this.transform;
    }

    public StructureTransform flip(boolean northSouth) {
        StructureTransform.Mirror mirror = northSouth ? StructureTransform.Mirror.LEFT_RIGHT
                : StructureTransform.Mirror.FRONT_BACK;
        this.transform = this.transform.then(new StructureTransform(StructureTransform.Rotation.NONE, mirror));
        refreshPreview();
        return this.transform;
    }

    public Preview preview(Identifier dimension, int x, int y, int z, StructurePlacement.Rule rule) {
        requireLoaded();
        long visible = loaded.cells().stream().filter(cell -> cell.state() != Blocks.AIR).count();
        if (visible > MAX_PREVIEW_CELLS) throw new IllegalStateException(
                "Structure hat zu viele sichtbare Zellen fuer eine Vorschau: " + visible);
        this.preview = new Preview(dimension, loaded, x, y, z, transform, rule,
                bounds(loaded, x, y, z, transform));
        return this.preview;
    }

    public void clearPreview() { this.preview = null; }

    private void refreshPreview() {
        if (this.preview != null) this.preview = new Preview(preview.dimension(), loaded,
                preview.x(), preview.y(), preview.z(), transform, preview.rule(),
                bounds(loaded, preview.x(), preview.y(), preview.z(), transform));
    }

    public StructurePlacement.Result paste(Dimension dimension, int x, int y, int z,
                                           StructurePlacement.Rule rule) {
        requireLoaded();
        StructurePlacement.Plan plan = placement.prepareInWorld(loaded, dimension, x, y, z, transform, rule);
        if (plan.count() > MAX_HISTORY_CELLS) throw new IllegalStateException(
                "Paste ist zu gross fuer garantiertes Undo: " + plan.count() + " Zellen");
        DataTag[] beforeTags = captureTags(dimension, plan.positions());
        StructurePlacement.Result result = placement.applyPlan(dimension, plan, true);
        DataTag[] afterTags = captureTags(dimension, plan.positions());
        History history = histories.computeIfAbsent(dimension.getDimensionId(), ignored -> new History());
        history.redo.clear();
        history.undo.addLast(new Transaction(plan, beforeTags, afterTags));
        trim(history);
        this.preview = null;
        return result;
    }

    public HistoryResult undo(Dimension dimension, int amount) { return moveHistory(dimension, amount, false); }
    public HistoryResult redo(Dimension dimension, int amount) { return moveHistory(dimension, amount, true); }

    private HistoryResult moveHistory(Dimension dimension, int amount, boolean redo) {
        if (amount <= 0) throw new IllegalArgumentException("Anzahl muss positiv sein");
        History history = histories.computeIfAbsent(dimension.getDimensionId(), ignored -> new History());
        ArrayDeque<Transaction> source = redo ? history.redo : history.undo;
        ArrayDeque<Transaction> target = redo ? history.undo : history.redo;
        int operations = 0, cells = 0;
        while (operations < amount && !source.isEmpty()) {
            Transaction transaction = source.removeLast();
            placement.applyPlan(dimension, transaction.plan, redo);
            restoreTags(dimension, transaction.plan.positions(), redo ? transaction.afterTags : transaction.beforeTags);
            target.addLast(transaction);
            operations++;
            cells += transaction.plan.count();
        }
        trim(history);
        return new HistoryResult(operations, cells);
    }

    private static DataTag[] captureTags(Dimension dimension, long[] positions) {
        DataTag[] result = new DataTag[positions.length];
        for (int i = 0; i < positions.length; i++) {
            BlockEntity entity = dimension.getBlockEntity(BlockPos.unpackX(positions[i]),
                    BlockPos.unpackY(positions[i]), BlockPos.unpackZ(positions[i]));
            if (entity != null) {
                DataTag tag = new DataTag();
                entity.save(tag);
                result[i] = tag.copy();
            }
        }
        return result;
    }

    private static void restoreTags(Dimension dimension, long[] positions, DataTag[] tags) {
        for (int i = 0; i < positions.length; i++) {
            if (tags[i] == null) continue;
            BlockEntity entity = dimension.getBlockEntity(BlockPos.unpackX(positions[i]),
                    BlockPos.unpackY(positions[i]), BlockPos.unpackZ(positions[i]));
            if (entity != null) entity.load(tags[i].copy());
        }
    }

    private static void trim(History history) {
        while (history.undo.size() > MAX_HISTORY) history.undo.removeFirst();
        int cells = history.undo.stream().mapToInt(t -> t.plan.count()).sum()
                + history.redo.stream().mapToInt(t -> t.plan.count()).sum();
        while (cells > MAX_HISTORY_CELLS && history.undo.size() > 1) cells -= history.undo.removeFirst().plan.count();
        while (cells > MAX_HISTORY_CELLS && !history.redo.isEmpty()) cells -= history.redo.removeFirst().plan.count();
    }

    private void requireLoaded() {
        if (loaded == null) throw new IllegalStateException("Keine Structure geladen");
    }

    public static StructureBounds bounds(StructureTemplate template, int x, int y, int z,
                                         StructureTransform transform) {
        int[] xs = {0, template.sizeX() - 1, 0, template.sizeX() - 1};
        int[] zs = {0, 0, template.sizeZ() - 1, template.sizeZ() - 1};
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
        for (int i = 0; i < 4; i++) {
            int tx = x + transform.transformedX(xs[i] - template.anchorX(), zs[i] - template.anchorZ());
            int tz = z + transform.transformedZ(xs[i] - template.anchorX(), zs[i] - template.anchorZ());
            minX = Math.min(minX, tx); maxX = Math.max(maxX, tx);
            minZ = Math.min(minZ, tz); maxZ = Math.max(maxZ, tz);
        }
        return new StructureBounds(minX, y - template.anchorY(), minZ, maxX,
                y - template.anchorY() + template.sizeY() - 1, maxZ);
    }

    private record Transaction(StructurePlacement.Plan plan, DataTag[] beforeTags, DataTag[] afterTags) {}
    private static final class History {
        final ArrayDeque<Transaction> undo = new ArrayDeque<>();
        final ArrayDeque<Transaction> redo = new ArrayDeque<>();
    }
}
