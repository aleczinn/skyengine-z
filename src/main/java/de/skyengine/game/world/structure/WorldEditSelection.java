package de.skyengine.game.world.structure;

import de.skyengine.core.i18n.I18n;
import de.skyengine.game.world.block.BlockPos;
import de.skyengine.game.world.block.Direction;
import de.skyengine.game.world.block.Identifier;

/** Allgemeine, spielerbezogene Quaderselektion ohne Structure-spezifischen Anker. */
public record WorldEditSelection(Identifier dimension, BlockPos pos1, BlockPos pos2) {
    public WorldEditSelection(Identifier dimension) {
        this(dimension, null, null);
    }

    public WorldEditSelection withPos1(int x, int y, int z) {
        return new WorldEditSelection(dimension, new BlockPos(x, y, z), pos2);
    }

    public WorldEditSelection withPos2(int x, int y, int z) {
        return new WorldEditSelection(dimension, pos1, new BlockPos(x, y, z));
    }

    public boolean complete() {
        return dimension != null && pos1 != null && pos2 != null;
    }

    public StructureBounds bounds() {
        requireComplete();
        return new StructureBounds(Math.min(pos1.x(), pos2.x()), Math.min(pos1.y(), pos2.y()),
                Math.min(pos1.z(), pos2.z()), Math.max(pos1.x(), pos2.x()),
                Math.max(pos1.y(), pos2.y()), Math.max(pos1.z(), pos2.z()));
    }

    public boolean contains(BlockPos pos) {
        if (pos == null || !complete()) return false;
        StructureBounds b = bounds();
        return pos.x() >= b.minX() && pos.x() <= b.maxX()
                && pos.y() >= b.minY() && pos.y() <= b.maxY()
                && pos.z() >= b.minZ() && pos.z() <= b.maxZ();
    }

    /** Erweitert ausschliesslich die Seite, die in {@code direction} liegt. */
    public WorldEditSelection expand(Direction direction, int amount) {
        requirePositive(amount);
        return moveFace(direction, amount, false);
    }

    /** Zieht ausschliesslich die in {@code direction} liegende Seite nach innen. */
    public WorldEditSelection contract(Direction direction, int amount) {
        requirePositive(amount);
        StructureBounds b = bounds();
        int size = switch (direction.axis()) {
            case X -> b.sizeX();
            case Y -> b.sizeY();
            case Z -> b.sizeZ();
        };
        if (amount >= size) throw new IllegalArgumentException(I18n.tr("command.worldedit.contract_empty"));
        return moveFace(direction, amount, true);
    }

    private WorldEditSelection moveFace(Direction direction, int amount, boolean inward) {
        requireComplete();
        int signed = inward ? -amount : amount;
        int dx = direction.offsetX() * signed;
        int dy = direction.offsetY() * signed;
        int dz = direction.offsetZ() * signed;
        boolean moveFirst = switch (direction) {
            case EAST -> pos1.x() >= pos2.x();
            case WEST -> pos1.x() <= pos2.x();
            case UP -> pos1.y() >= pos2.y();
            case DOWN -> pos1.y() <= pos2.y();
            case SOUTH -> pos1.z() >= pos2.z();
            case NORTH -> pos1.z() <= pos2.z();
        };
        BlockPos moving = moveFirst ? pos1 : pos2;
        BlockPos changed = new BlockPos(Math.addExact(moving.x(), dx),
                Math.addExact(moving.y(), dy), Math.addExact(moving.z(), dz));
        return moveFirst ? new WorldEditSelection(dimension, changed, pos2)
                : new WorldEditSelection(dimension, pos1, changed);
    }

    private void requireComplete() {
        if (!complete()) throw new IllegalStateException(I18n.tr("command.worldedit.incomplete_selection"));
    }

    private static void requirePositive(int amount) {
        if (amount <= 0) throw new IllegalArgumentException(I18n.tr("command.worldedit.positive_value"));
    }
}
