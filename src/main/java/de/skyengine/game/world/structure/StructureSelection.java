package de.skyengine.game.world.structure;

import de.skyengine.game.world.block.BlockPos;
import de.skyengine.game.world.block.Identifier;

/** Spielerbezogene Authoring-Auswahl mit optionalem, nachtraeglich setzbarem Anker. */
public record StructureSelection(Identifier dimension, BlockPos pos1, BlockPos pos2, BlockPos anchor) {
    public StructureSelection(Identifier dimension) { this(dimension, null, null, null); }
    public StructureSelection withPos1(int x, int y, int z) {
        return validated(new StructureSelection(dimension, new BlockPos(x, y, z), pos2, anchor));
    }
    public StructureSelection withPos2(int x, int y, int z) {
        return validated(new StructureSelection(dimension, pos1, new BlockPos(x, y, z), anchor));
    }
    public StructureSelection withAnchor(int x, int y, int z) {
        StructureSelection result = new StructureSelection(dimension, pos1, pos2, new BlockPos(x, y, z));
        if (!result.complete()) throw new IllegalStateException("Zuerst pos1 und pos2 setzen");
        if (!result.contains(result.anchor)) throw new IllegalArgumentException("Anker liegt ausserhalb der Auswahl");
        return result;
    }
    public StructureSelection resetAnchor() { return new StructureSelection(dimension, pos1, pos2, null); }
    public BlockPos effectiveAnchor() {
        if (!complete()) throw new IllegalStateException("Struktur-Auswahl ist unvollstaendig");
        return anchor == null ? pos1 : anchor;
    }
    public boolean complete() { return dimension != null && pos1 != null && pos2 != null; }

    public StructureBounds bounds() {
        if (!complete()) throw new IllegalStateException("Struktur-Auswahl ist unvollstaendig");
        return new StructureBounds(Math.min(pos1.x(), pos2.x()), Math.min(pos1.y(), pos2.y()),
                Math.min(pos1.z(), pos2.z()), Math.max(pos1.x(), pos2.x()),
                Math.max(pos1.y(), pos2.y()), Math.max(pos1.z(), pos2.z()));
    }

    private boolean contains(BlockPos pos) {
        StructureBounds b = bounds();
        return pos.x() >= b.minX() && pos.x() <= b.maxX() && pos.y() >= b.minY() && pos.y() <= b.maxY()
                && pos.z() >= b.minZ() && pos.z() <= b.maxZ();
    }

    private static StructureSelection validated(StructureSelection selection) {
        return selection.anchor != null && selection.complete() && !selection.contains(selection.anchor)
                ? selection.resetAnchor() : selection;
    }
}
