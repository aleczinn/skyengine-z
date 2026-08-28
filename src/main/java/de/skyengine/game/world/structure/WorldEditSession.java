package de.skyengine.game.world.structure;

import de.skyengine.core.i18n.I18n;
import de.skyengine.game.world.Dimension;
import de.skyengine.game.world.block.BlockPos;
import de.skyengine.game.world.block.Blocks;
import de.skyengine.game.world.block.Direction;
import de.skyengine.game.world.block.Identifier;
import de.skyengine.game.world.block.entity.BlockEntity;
import de.skyengine.game.world.block.entity.BlockEntityType;
import de.skyengine.game.world.block.entity.DataTag;
import de.skyengine.game.world.block.registry.Registries;
import de.skyengine.game.world.chunk.Chunk;

import java.io.IOException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.IntPredicate;

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

    /** Ursprung fuer Clipboard-Operationen und das Speichern nativer Strukturen. */
    public enum OperationOrigin { PLAYER, ANCHOR }

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

    public StructureTemplate save(Dimension dimension, String reference, boolean includeAir,
                                  boolean overwrite, int playerX, int playerY, int playerZ,
                                  OperationOrigin origin) throws IOException {
        requireSelection(dimension.getDimensionId(), true);
        validateSelectionReady(dimension);
        BlockPos anchor;
        if (origin == OperationOrigin.ANCHOR) {
            if (structureAnchor == null) throw new IllegalStateException(
                    I18n.tr("command.worldedit.anchor_missing"));
            anchor = structureAnchor;
        } else {
            anchor = new BlockPos(playerX, playerY, playerZ);
            if (!selection.contains(anchor)) throw new IllegalArgumentException(
                    I18n.tr("command.structure.player_outside"));
        }
        StructureTemplate template = builder.capture(dimension, selection,
                templates.idForNewReference(reference), includeAir, anchor);
        templates.saveAuthored(template, overwrite);
        this.clipboard = WorldEditClipboard.fromTemplate(template);
        this.preview = null;
        return template;
    }

    public StructureTemplate load(String reference) throws IOException {
        StructureTemplate template = templates.get(reference);
        if (template == null) throw new IOException(I18n.tr("command.structure.not_found", reference));
        this.clipboard = WorldEditClipboard.fromTemplate(template);
        this.preview = null;
        return template;
    }

    /** Kopiert die komplette Selektion inklusive expliziter Luft relativ zur Spielerposition. */
    public WorldEditClipboard copy(Dimension dimension, int playerX, int playerY, int playerZ) {
        return copy(dimension, playerX, playerY, playerZ, OperationOrigin.PLAYER);
    }

    /** Kopiert relativ zur Spielerposition oder zum explizit markierten Structure-Anker. */
    public WorldEditClipboard copy(Dimension dimension, int playerX, int playerY, int playerZ,
                                   OperationOrigin origin) {
        WorldEditClipboard copied = captureClipboard(dimension, playerX, playerY, playerZ, origin);
        this.clipboard = copied;
        this.preview = null;
        return copied;
    }

    private WorldEditClipboard captureClipboard(Dimension dimension, int playerX, int playerY,
                                                int playerZ, OperationOrigin origin) {
        requireSelection(dimension.getDimensionId(), true);
        validateSelectionReady(dimension);
        StructureBounds bounds = selection.bounds();
        BlockPos sourceOrigin;
        if (origin == OperationOrigin.ANCHOR) {
            if (structureAnchor == null) throw new IllegalStateException(
                    I18n.tr("command.worldedit.anchor_missing"));
            sourceOrigin = structureAnchor;
        } else {
            sourceOrigin = new BlockPos(playerX, playerY, playerZ);
        }
        StructureTemplate template = builder.capture(dimension, selection,
                Identifier.of("clipboard"), true, selection.pos1());
        return new WorldEditClipboard(template, sourceOrigin.x() - bounds.minX(),
                sourceOrigin.y() - bounds.minY(), sourceOrigin.z() - bounds.minZ(),
                StructureTransform.IDENTITY);
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
        return paste(dimension, x, y, z, rule, false);
    }

    public StructurePlacement.Result paste(Dimension dimension, int x, int y, int z,
                                           StructurePlacement.Rule rule, boolean selectBounds) {
        requireClipboard();
        StructureBounds pastedBounds = selectBounds ? bounds(clipboard, x, y, z) : null;
        StructurePlacement.Plan plan = placement.prepareInWorld(clipboard.template(),
                clipboard.originX(), clipboard.originY(), clipboard.originZ(), dimension,
                x, y, z, clipboard.transform(), rule);
        StructurePlacement.Result result = history.apply(dimension, placement, plan);
        if (selectBounds && result.complete()) {
            this.selection = new WorldEditSelection(dimension.getDimensionId(),
                    new BlockPos(pastedBounds.minX(), pastedBounds.minY(), pastedBounds.minZ()),
                    new BlockPos(pastedBounds.maxX(), pastedBounds.maxY(), pastedBounds.maxZ()));
            /* //paste --selection erzeugt eine allgemeine WorldEdit-Selektion. Ein alter
               Authoring-Anker darf darin weder erscheinen noch spaeter versehentlich fuer
               //copy --anchor verwendet werden. */
            this.structureAnchor = null;
        }
        this.preview = null;
        return result;
    }

    /** Fuellt die Selektion als genau eine undo-faehige Batch-Transaktion. */
    public StructurePlacement.Result setBlock(Dimension dimension, int state) {
        return replace(dimension, ignored -> true, state);
    }

    /** Ersetzt alle von {@code matcher} akzeptierten Zellen als eine History-Transaktion. */
    public StructurePlacement.Result replace(Dimension dimension, IntPredicate matcher, int state) {
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
                    if (!matcher.test(existing)) { skipped++; continue; }
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

    /** Kopiert wie //copy und leert danach die Auswahl; Clipboard-Aenderung erst nach Erfolg. */
    public StructurePlacement.Result cut(Dimension dimension, int playerX, int playerY, int playerZ,
                                         OperationOrigin origin) {
        WorldEditClipboard copied = captureClipboard(dimension, playerX, playerY, playerZ, origin);
        StructurePlacement.Result result = replace(dimension, ignored -> true, Blocks.AIR);
        if (result.complete()) {
            this.clipboard = copied;
            this.preview = null;
        }
        return result;
    }

    /** Wiederholt die Auswahl direkt angrenzend in Blickrichtung; das Original bleibt bestehen. */
    public StructurePlacement.Result stack(Dimension dimension, Direction direction, int count) {
        if (count <= 0) throw new IllegalArgumentException(I18n.tr("command.worldedit.positive_value"));
        SelectionSnapshot source = captureSelection(dimension);
        StructureBounds bounds = source.bounds();
        long requested = Math.multiplyExact(bounds.volume(), (long) count);
        if (requested > WorldEditHistory.MAX_CELLS) throw new IllegalStateException(
                I18n.tr("command.worldedit.edit_too_large", requested));
        Map<Long, EditCell> finalCells = new LinkedHashMap<>((int) Math.min(requested * 4L / 3L + 1,
                WorldEditHistory.MAX_CELLS));
        int stepX = direction.offsetX() * bounds.sizeX();
        int stepY = direction.offsetY() * bounds.sizeY();
        int stepZ = direction.offsetZ() * bounds.sizeZ();
        for (int copy = 1; copy <= count; copy++) {
            int dx = Math.multiplyExact(stepX, copy);
            int dy = Math.multiplyExact(stepY, copy);
            int dz = Math.multiplyExact(stepZ, copy);
            for (SourceCell cell : source.cells()) {
                putTranslated(finalCells, cell, dx, dy, dz);
            }
        }
        return applyFinal(dimension, finalCells);
    }

    /** Verschiebt einen vorab aufgenommenen Snapshot ueberlappungssicher; Selektion bleibt. */
    public StructurePlacement.Result move(Dimension dimension, Direction direction, int distance) {
        if (distance <= 0) throw new IllegalArgumentException(I18n.tr("command.worldedit.positive_value"));
        SelectionSnapshot source = captureSelection(dimension);
        long maximum = Math.multiplyExact(source.bounds().volume(), 2L);
        if (maximum > WorldEditHistory.MAX_CELLS * 2L) throw new IllegalStateException(
                I18n.tr("command.worldedit.selection_too_large", source.bounds().volume()));
        Map<Long, EditCell> finalCells = new LinkedHashMap<>();
        for (SourceCell cell : source.cells()) finalCells.put(cell.position(), new EditCell(Blocks.AIR, null));
        int dx = Math.multiplyExact(direction.offsetX(), distance);
        int dy = Math.multiplyExact(direction.offsetY(), distance);
        int dz = Math.multiplyExact(direction.offsetZ(), distance);
        for (SourceCell cell : source.cells()) putTranslated(finalCells, cell, dx, dy, dz);
        if (finalCells.size() > WorldEditHistory.MAX_CELLS) throw new IllegalStateException(
                I18n.tr("command.worldedit.edit_too_large", finalCells.size()));
        return applyFinal(dimension, finalCells);
    }

    /** Rekonstruiert die Auswahl aus Terrain- und Feature-Pass der aktiven Dimension. */
    public StructurePlacement.Result regenerate(Dimension dimension) {
        requireSelection(dimension.getDimensionId(), true);
        validateSelectionReady(dimension);
        if (!dimension.supportsRegeneration()) throw new IllegalStateException(
                I18n.tr("command.worldedit.regen_imported"));
        StructureBounds bounds = selection.bounds();
        if (bounds.volume() > WorldEditHistory.MAX_CELLS) throw new IllegalStateException(
                I18n.tr("command.worldedit.selection_too_large", bounds.volume()));
        Map<Long, EditCell> finalCells = new LinkedHashMap<>((int) Math.min(
                bounds.volume() * 4L / 3L + 1, WorldEditHistory.MAX_CELLS));
        int minChunkX = bounds.minX() >> de.skyengine.game.world.chunk.ChunkSection.SHIFT;
        int maxChunkX = bounds.maxX() >> de.skyengine.game.world.chunk.ChunkSection.SHIFT;
        int minChunkZ = bounds.minZ() >> de.skyengine.game.world.chunk.ChunkSection.SHIFT;
        int maxChunkZ = bounds.maxZ() >> de.skyengine.game.world.chunk.ChunkSection.SHIFT;
        for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
            for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
                Chunk generated = dimension.generateWorldgenSnapshot(chunkX, chunkZ);
                int fromX = Math.max(bounds.minX(), chunkX << de.skyengine.game.world.chunk.ChunkSection.SHIFT);
                int toX = Math.min(bounds.maxX(), ((chunkX + 1) << de.skyengine.game.world.chunk.ChunkSection.SHIFT) - 1);
                int fromZ = Math.max(bounds.minZ(), chunkZ << de.skyengine.game.world.chunk.ChunkSection.SHIFT);
                int toZ = Math.min(bounds.maxZ(), ((chunkZ + 1) << de.skyengine.game.world.chunk.ChunkSection.SHIFT) - 1);
                for (int y = bounds.minY(); y <= bounds.maxY(); y++) {
                    for (int z = fromZ; z <= toZ; z++) {
                        for (int x = fromX; x <= toX; x++) {
                            int state = generated.getBlock(x & 31, y, z & 31);
                            BlockEntity entity = generated.getBlockEntity(x & 31, y, z & 31);
                            finalCells.put(BlockPos.asLong(x, y, z), new EditCell(state,
                                    snapshotBlockEntity(entity, x, y, z, state)));
                        }
                    }
                }
            }
        }
        return applyFinal(dimension, finalCells);
    }

    private SelectionSnapshot captureSelection(Dimension dimension) {
        requireSelection(dimension.getDimensionId(), true);
        validateSelectionReady(dimension);
        StructureBounds bounds = selection.bounds();
        if (bounds.volume() > WorldEditHistory.MAX_CELLS) throw new IllegalStateException(
                I18n.tr("command.worldedit.selection_too_large", bounds.volume()));
        SourceCell[] cells = new SourceCell[(int) bounds.volume()];
        int index = 0;
        for (int y = bounds.minY(); y <= bounds.maxY(); y++) {
            for (int z = bounds.minZ(); z <= bounds.maxZ(); z++) {
                for (int x = bounds.minX(); x <= bounds.maxX(); x++) {
                    int state = dimension.getBlock(x, y, z);
                    cells[index++] = new SourceCell(BlockPos.asLong(x, y, z), state,
                            snapshotBlockEntity(dimension, x, y, z, state));
                }
            }
        }
        return new SelectionSnapshot(bounds, cells);
    }

    private static StructureTemplate.BlockEntitySnapshot snapshotBlockEntity(
            Dimension dimension, int x, int y, int z, int state) {
        return snapshotBlockEntity(dimension.getBlockEntity(x, y, z), x, y, z, state);
    }

    private static StructureTemplate.BlockEntitySnapshot snapshotBlockEntity(
            BlockEntity entity, int x, int y, int z, int state) {
        BlockEntityType<?> expected = Blocks.getState(state).getBlock().getBlockEntityType();
        if (expected != null && !expected.isStructureSerializable()) {
            throw new IllegalArgumentException("Kurzlebige BlockEntity kann bei "
                    + x + " " + y + " " + z + " nicht editiert werden");
        }
        if (entity == null) return null;
        Identifier type = Registries.BLOCK_ENTITY.idOf(entity.getType());
        if (type == null) throw new IllegalArgumentException("BlockEntity ohne Registry-Typ bei "
                + x + " " + y + " " + z);
        DataTag data = new DataTag();
        entity.save(data);
        return new StructureTemplate.BlockEntitySnapshot(type, data);
    }

    private static void putTranslated(Map<Long, EditCell> target, SourceCell cell,
                                      int dx, int dy, int dz) {
        int x = Math.addExact(BlockPos.unpackX(cell.position()), dx);
        int y = Math.addExact(BlockPos.unpackY(cell.position()), dy);
        int z = Math.addExact(BlockPos.unpackZ(cell.position()), dz);
        if (y < 0 || y >= Chunk.HEIGHT) throw new IllegalArgumentException(
                I18n.tr("command.worldedit.world_height", Chunk.HEIGHT - 1));
        target.put(BlockPos.asLong(x, y, z), new EditCell(cell.state(), cell.blockEntity()));
    }

    private StructurePlacement.Result applyFinal(Dimension dimension, Map<Long, EditCell> finalCells) {
        long[] positions = new long[finalCells.size()];
        int[] before = new int[finalCells.size()];
        int[] after = new int[finalCells.size()];
        StructureTemplate.BlockEntitySnapshot[] blockEntities =
                new StructureTemplate.BlockEntitySnapshot[finalCells.size()];
        int count = 0, skipped = 0;
        for (Map.Entry<Long, EditCell> entry : finalCells.entrySet()) {
            long position = entry.getKey();
            int x = BlockPos.unpackX(position), y = BlockPos.unpackY(position), z = BlockPos.unpackZ(position);
            if (!dimension.isPositionEditable(x, y, z)) throw new IllegalStateException(
                    I18n.tr("command.worldedit.target_not_ready", x, y, z));
            EditCell cell = entry.getValue();
            int existing = dimension.getBlock(x, y, z);
            if (existing == cell.state() && cell.blockEntity() == null) { skipped++; continue; }
            positions[count] = position;
            before[count] = existing;
            after[count] = cell.state();
            blockEntities[count] = cell.blockEntity();
            count++;
        }
        StructurePlacement.Plan plan = new StructurePlacement.Plan(Arrays.copyOf(positions, count),
                Arrays.copyOf(before, count), Arrays.copyOf(after, count),
                Arrays.copyOf(blockEntities, count), count, skipped);
        return history.apply(dimension, placement, plan);
    }

    private record SourceCell(long position, int state,
                              StructureTemplate.BlockEntitySnapshot blockEntity) {}
    private record SelectionSnapshot(StructureBounds bounds, SourceCell[] cells) {}
    private record EditCell(int state, StructureTemplate.BlockEntitySnapshot blockEntity) {}

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
