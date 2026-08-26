package de.skyengine.game.world.structure;

import de.skyengine.core.i18n.I18n;
import de.skyengine.game.world.Dimension;
import de.skyengine.game.world.block.BlockPos;
import de.skyengine.game.world.block.Blocks;
import de.skyengine.game.world.block.Direction;
import de.skyengine.game.world.block.Identifier;
import de.skyengine.game.world.chunk.Chunk;

import java.io.IOException;
import java.util.Arrays;

/** Nicht persistierte, UUID-gebundene Sitzung des allgemeinen In-Engine-Welteditors. */
public final class WorldEditSession {
    public static final int MAX_PREVIEW_CELLS = 500_000;

    /** Erweiterbare Bedienmodi der nur per Command erhaeltlichen Debug-Axt. */
    public enum ToolMode {
        SELECTION,
        ANCHOR;

        public ToolMode cycle(int direction) {
            int step = direction < 0 ? -1 : 1;
            ToolMode[] values = values();
            return values[Math.floorMod(ordinal() + step, values.length)];
        }
    }

    /** Ursprung, um den ein frisch kopiertes Clipboard spaeter platziert und transformiert wird. */
    public enum CopyOrigin { PLAYER, ANCHOR }

    public record Preview(Identifier dimension, WorldEditClipboard clipboard, int x, int y, int z,
                          StructurePlacement.Rule rule, StructureBounds bounds) {
        public StructureTemplate template() { return clipboard.template(); }
        public StructureTransform transform() { return clipboard.transform(); }
        public int originX() { return clipboard.originX(); }
        public int originY() { return clipboard.originY(); }
        public int originZ() { return clipboard.originZ(); }
    }

    private final StructureTemplateManager templates;
    private final StructureTemplateBuilder builder = new StructureTemplateBuilder();
    private final StructurePlacement placement = new StructurePlacement();
    private final WorldEditHistory history = new WorldEditHistory();
    private WorldEditSelection selection;
    private BlockPos structureAnchor;
    private WorldEditClipboard clipboard;
    private Preview preview;
    private ToolMode toolMode = ToolMode.SELECTION;

    WorldEditSession(StructureTemplateManager templates) {
        this.templates = templates;
    }

    public WorldEditSelection selection() { return selection; }
    public BlockPos structureAnchor() { return structureAnchor; }
    public WorldEditClipboard clipboard() { return clipboard; }
    public StructureTemplate loaded() { return clipboard == null ? null : clipboard.template(); }
    public StructureTransform transform() {
        return clipboard == null ? StructureTransform.IDENTITY : clipboard.transform();
    }
    public Preview preview() { return preview; }
    public ToolMode toolMode() { return toolMode; }
    public StructureTemplateManager templates() { return templates; }

    public ToolMode cycleToolMode(int direction) {
        if (direction == 0) return toolMode;
        this.toolMode = this.toolMode.cycle(direction);
        return this.toolMode;
    }

    /** Entfernt Selektion und ihren Authoring-Anker; Clipboard, Preview und History bleiben. */
    public void clearSelection() {
        this.selection = null;
        this.structureAnchor = null;
    }

    public void pos1(Identifier dimension, int x, int y, int z) {
        if (selection == null || !dimension.equals(selection.dimension())) selection = new WorldEditSelection(dimension);
        selection = selection.withPos1(x, y, z);
        validateStructureAnchor();
    }

    public void pos2(Identifier dimension, int x, int y, int z) {
        if (selection == null || !dimension.equals(selection.dimension())) selection = new WorldEditSelection(dimension);
        selection = selection.withPos2(x, y, z);
        validateStructureAnchor();
    }

    public WorldEditSelection expand(Identifier dimension, Direction direction, int amount) {
        requireSelection(dimension, true);
        WorldEditSelection changed = selection.expand(direction, amount);
        validateWorldHeight(changed);
        this.selection = changed;
        validateStructureAnchor();
        return changed;
    }

    public WorldEditSelection contract(Identifier dimension, Direction direction, int amount) {
        requireSelection(dimension, true);
        WorldEditSelection changed = selection.contract(direction, amount);
        validateWorldHeight(changed);
        this.selection = changed;
        validateStructureAnchor();
        return changed;
    }

    public void anchor(Identifier dimension, int x, int y, int z) {
        requireSelection(dimension, true);
        BlockPos value = new BlockPos(x, y, z);
        if (!selection.contains(value)) throw new IllegalArgumentException(I18n.tr("command.worldedit.anchor_outside"));
        this.structureAnchor = value;
    }

    public void resetAnchor(Identifier dimension) {
        requireSelection(dimension, true);
        this.structureAnchor = null;
    }

    public StructureTemplate save(Dimension dimension, String reference, boolean includeAir,
                                  boolean overwrite) throws IOException {
        requireSelection(dimension.getDimensionId(), true);
        validateSelectionReady(dimension);
        BlockPos anchor = structureAnchor == null ? selection.pos1() : structureAnchor;
        StructureTemplate template = builder.capture(dimension, selection,
                templates.idForNewReference(reference), includeAir, anchor);
        templates.saveAuthored(template, overwrite);
        this.clipboard = WorldEditClipboard.fromTemplate(template);
        this.preview = null;
        return template;
    }

    public StructureTemplate load(String reference) throws IOException {
        StructureTemplate template = templates.get(reference);
        if (template == null) throw new IOException("Structure nicht gefunden: " + reference);
        this.clipboard = WorldEditClipboard.fromTemplate(template);
        this.preview = null;
        return template;
    }

    /** Kopiert die komplette Selektion inklusive expliziter Luft relativ zur Spielerposition. */
    public WorldEditClipboard copy(Dimension dimension, int playerX, int playerY, int playerZ) {
        return copy(dimension, playerX, playerY, playerZ, CopyOrigin.PLAYER);
    }

    /** Kopiert relativ zur Spielerposition oder zum explizit markierten Structure-Anker. */
    public WorldEditClipboard copy(Dimension dimension, int playerX, int playerY, int playerZ,
                                   CopyOrigin origin) {
        requireSelection(dimension.getDimensionId(), true);
        validateSelectionReady(dimension);
        StructureBounds bounds = selection.bounds();
        BlockPos sourceOrigin;
        if (origin == CopyOrigin.ANCHOR) {
            if (structureAnchor == null) throw new IllegalStateException(
                    I18n.tr("command.worldedit.anchor_missing"));
            sourceOrigin = structureAnchor;
        } else {
            sourceOrigin = new BlockPos(playerX, playerY, playerZ);
        }
        StructureTemplate template = builder.capture(dimension, selection,
                Identifier.of("skyengine:clipboard"), true, selection.pos1());
        this.clipboard = new WorldEditClipboard(template, sourceOrigin.x() - bounds.minX(),
                sourceOrigin.y() - bounds.minY(), sourceOrigin.z() - bounds.minZ(),
                StructureTransform.IDENTITY);
        this.preview = null;
        return this.clipboard;
    }

    public StructureTransform rotate(int degrees) {
        requireClipboard();
        if (degrees % 90 != 0) throw new IllegalArgumentException(
                "Rotation muss ein Vielfaches von 90 Grad sein; freie Winkel passen nicht ins Voxelraster");
        int turns = Math.floorMod(degrees / 90, 4);
        StructureTransform changed = clipboard.transform().then(new StructureTransform(
                StructureTransform.Rotation.values()[turns], StructureTransform.Mirror.NONE));
        this.clipboard = clipboard.withTransform(changed);
        refreshPreview();
        return changed;
    }

    public StructureTransform flip(boolean northSouth) {
        requireClipboard();
        StructureTransform.Mirror mirror = northSouth ? StructureTransform.Mirror.LEFT_RIGHT
                : StructureTransform.Mirror.FRONT_BACK;
        StructureTransform changed = clipboard.transform().then(
                new StructureTransform(StructureTransform.Rotation.NONE, mirror));
        this.clipboard = clipboard.withTransform(changed);
        refreshPreview();
        return changed;
    }

    public Preview preview(Identifier dimension, int x, int y, int z, StructurePlacement.Rule rule) {
        requireClipboard();
        long visible = clipboard.template().cells().stream().filter(cell -> cell.state() != Blocks.AIR).count();
        if (visible > MAX_PREVIEW_CELLS) throw new IllegalStateException(
                I18n.tr("command.worldedit.preview_too_large", visible));
        this.preview = new Preview(dimension, clipboard, x, y, z, rule,
                bounds(clipboard, x, y, z));
        return this.preview;
    }

    public void clearPreview() { this.preview = null; }

    public StructurePlacement.Result paste(Dimension dimension, int x, int y, int z,
                                           StructurePlacement.Rule rule) {
        requireClipboard();
        StructurePlacement.Plan plan = placement.prepareInWorld(clipboard.template(),
                clipboard.originX(), clipboard.originY(), clipboard.originZ(), dimension,
                x, y, z, clipboard.transform(), rule);
        StructurePlacement.Result result = history.apply(dimension, placement, plan);
        this.preview = null;
        return result;
    }

    /** Fuellt die Selektion als genau eine undo-faehige Batch-Transaktion. */
    public StructurePlacement.Result setBlock(Dimension dimension, int state) {
        requireSelection(dimension.getDimensionId(), true);
        StructureBounds bounds = selection.bounds();
        long volume = bounds.volume();
        if (volume > WorldEditHistory.MAX_CELLS) throw new IllegalStateException(
                I18n.tr("command.worldedit.selection_too_large", volume));
        long[] positions = new long[(int) volume];
        int[] before = new int[(int) volume];
        int[] after = new int[(int) volume];
        int count = 0, skipped = 0;
        for (int y = bounds.minY(); y <= bounds.maxY(); y++) {
            for (int z = bounds.minZ(); z <= bounds.maxZ(); z++) {
                for (int x = bounds.minX(); x <= bounds.maxX(); x++) {
                    if (!dimension.isPositionEditable(x, y, z)) throw new IllegalStateException(
                            I18n.tr("command.worldedit.target_not_ready", x, y, z));
                    int existing = dimension.getBlock(x, y, z);
                    if (existing == state) { skipped++; continue; }
                    positions[count] = BlockPos.asLong(x, y, z);
                    before[count] = existing;
                    after[count] = state;
                    count++;
                }
            }
        }
        StructurePlacement.Plan plan = new StructurePlacement.Plan(Arrays.copyOf(positions, count),
                Arrays.copyOf(before, count), Arrays.copyOf(after, count), count, skipped);
        return history.apply(dimension, placement, plan);
    }

    public WorldEditHistory.Result undo(Dimension dimension, int amount) {
        return history.undo(dimension, placement, amount);
    }

    public WorldEditHistory.Result redo(Dimension dimension, int amount) {
        return history.redo(dimension, placement, amount);
    }

    private void refreshPreview() {
        if (preview != null) preview = new Preview(preview.dimension(), clipboard,
                preview.x(), preview.y(), preview.z(), preview.rule(),
                bounds(clipboard, preview.x(), preview.y(), preview.z()));
    }

    private void requireSelection(Identifier dimension, boolean complete) {
        if (selection == null || !dimension.equals(selection.dimension())) {
            throw new IllegalStateException(I18n.tr("command.worldedit.no_selection"));
        }
        if (complete && !selection.complete()) throw new IllegalStateException(
                I18n.tr("command.worldedit.incomplete_selection"));
    }

    private void requireClipboard() {
        if (clipboard == null) throw new IllegalStateException(I18n.tr("command.worldedit.clipboard_empty"));
    }

    private void validateStructureAnchor() {
        if (structureAnchor != null && (selection == null || !selection.contains(structureAnchor))) {
            structureAnchor = null;
        }
    }

    private static void validateWorldHeight(WorldEditSelection value) {
        StructureBounds bounds = value.bounds();
        if (bounds.minY() < 0 || bounds.maxY() >= Chunk.HEIGHT) {
            throw new IllegalArgumentException(I18n.tr("command.worldedit.world_height", Chunk.HEIGHT - 1));
        }
    }

    private void validateSelectionReady(Dimension dimension) {
        StructureBounds bounds = selection.bounds();
        for (int y = bounds.minY(); y <= bounds.maxY(); y++) {
            for (int z = bounds.minZ(); z <= bounds.maxZ(); z++) {
                for (int x = bounds.minX(); x <= bounds.maxX(); x++) {
                    if (!dimension.isPositionEditable(x, y, z)) throw new IllegalStateException(
                            I18n.tr("command.worldedit.target_not_ready", x, y, z));
                }
            }
        }
    }

    public static StructureBounds bounds(WorldEditClipboard clipboard, int x, int y, int z) {
        StructureTemplate template = clipboard.template();
        StructureTransform transform = clipboard.transform();
        int[] xs = {0, template.sizeX() - 1, 0, template.sizeX() - 1};
        int[] zs = {0, 0, template.sizeZ() - 1, template.sizeZ() - 1};
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
        for (int i = 0; i < 4; i++) {
            int tx = x + transform.transformedX(xs[i] - clipboard.originX(), zs[i] - clipboard.originZ());
            int tz = z + transform.transformedZ(xs[i] - clipboard.originX(), zs[i] - clipboard.originZ());
            minX = Math.min(minX, tx); maxX = Math.max(maxX, tx);
            minZ = Math.min(minZ, tz); maxZ = Math.max(maxZ, tz);
        }
        return new StructureBounds(minX, y - clipboard.originY(), minZ, maxX,
                y - clipboard.originY() + template.sizeY() - 1, maxZ);
    }
}
