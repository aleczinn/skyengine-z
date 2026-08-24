package de.skyengine.game.world.dimension;

import de.skyengine.game.world.World;
import de.skyengine.game.world.block.Blocks;
import de.skyengine.game.world.block.Direction;
import de.skyengine.game.world.block.Identifier;
import de.skyengine.game.world.block.state.BlockState;
import de.skyengine.game.world.block.state.Properties;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Erkennt, aktiviert und prueft rechteckige Obsidianrahmen. */
public final class NetherPortalShape {

    public static final int MIN_WIDTH = 2;
    public static final int MIN_HEIGHT = 3;
    public static final int MAX_WIDTH = 21;
    public static final int MAX_HEIGHT = 21;

    public record Shape(Direction.Axis axis, int minX, int bottomY, int minZ,
                        int width, int height) {
        public int centerX() { return axis == Direction.Axis.X ? minX + width / 2 : minX; }
        public int centerZ() { return axis == Direction.Axis.Z ? minZ + width / 2 : minZ; }
    }

    public record Collapse(Direction.Axis axis, double centerX, double centerY, double centerZ,
                           int width, int height, int blocks) {}

    private record Cell(int x, int y, int z) {}

    public static Shape find(World world, int x, int y, int z, boolean requirePortal) {
        Shape xShape = find(world, x, y, z, Direction.Axis.X, requirePortal);
        return xShape != null ? xShape : find(world, x, y, z, Direction.Axis.Z, requirePortal);
    }

    public static boolean activate(World world, int x, int y, int z) {
        Shape shape = find(world, x, y, z, false);
        if (shape == null) return false;
        BlockState portal = Blocks.getState(Blocks.NETHER_PORTAL)
                .with(Properties.HORIZONTAL_AXIS, shape.axis);
        List<Cell> written = new ArrayList<>(shape.width * shape.height);
        boolean[] failed = {false};
        forEachInterior(shape, (px, py, pz) -> {
            if (!failed[0] && world.setBlock(px, py, pz, portal.getId(), false)) {
                written.add(new Cell(px, py, pz));
            } else {
                failed[0] = true;
            }
        });
        if (failed[0] || written.size() != shape.width * shape.height) {
            for (Cell cell : written) world.setBlock(cell.x, cell.y, cell.z, Blocks.AIR, false);
            return false;
        }
        world.getPortalIndex().add(Identifier.of("skyengine:nether_portal"), shape);
        forEachInterior(shape, world::updateNeighbors);
        return true;
    }

    /** Aktiviert einen Rahmen auch dann, wenn der Klick eine Rahmenkante statt der Luft trifft. */
    public static boolean activateNear(World world, int x, int y, int z) {
        if (activate(world, x, y, z)) return true;
        for (int dy = -1; dy <= 1; dy++) {
            for (int dz = -1; dz <= 1; dz++) {
                for (int dx = -1; dx <= 1; dx++) {
                    if (dx == 0 && dy == 0 && dz == 0) continue;
                    if (activate(world, x + dx, y + dy, z + dz)) return true;
                }
            }
        }
        return false;
    }

    /** Entfernt eine zusammenhaengende Portaloberflaeche ohne Effekte pro Einzelblock. */
    public static Collapse collapse(World world, int x, int y, int z, Direction.Axis axis) {
        if (!portal(world, x, y, z, axis)) return null;
        ArrayDeque<Cell> open = new ArrayDeque<>();
        Set<Cell> cells = new HashSet<>();
        Cell source = new Cell(x, y, z);
        open.add(source);
        cells.add(source);
        int minX = x, maxX = x, minY = y, maxY = y, minZ = z, maxZ = z;
        while (!open.isEmpty()) {
            Cell cell = open.removeFirst();
            minX = Math.min(minX, cell.x); maxX = Math.max(maxX, cell.x);
            minY = Math.min(minY, cell.y); maxY = Math.max(maxY, cell.y);
            minZ = Math.min(minZ, cell.z); maxZ = Math.max(maxZ, cell.z);
            int sx = axis == Direction.Axis.X ? 1 : 0;
            int sz = axis == Direction.Axis.Z ? 1 : 0;
            addPortal(world, axis, cells, open, cell.x + sx, cell.y, cell.z + sz);
            addPortal(world, axis, cells, open, cell.x - sx, cell.y, cell.z - sz);
            addPortal(world, axis, cells, open, cell.x, cell.y + 1, cell.z);
            addPortal(world, axis, cells, open, cell.x, cell.y - 1, cell.z);
        }
        for (Cell cell : cells) {
            if (!cell.equals(source)) world.setBlock(cell.x, cell.y, cell.z, Blocks.AIR, false);
        }
        world.getPortalIndex().removeContaining(Identifier.of("skyengine:nether_portal"), x, y, z);
        int width = axis == Direction.Axis.X ? maxX - minX + 1 : maxZ - minZ + 1;
        return new Collapse(axis, (minX + maxX + 1) * 0.5, (minY + maxY + 1) * 0.5,
                (minZ + maxZ + 1) * 0.5, width, maxY - minY + 1, cells.size());
    }

    private static void addPortal(World world, Direction.Axis axis, Set<Cell> cells,
                                  ArrayDeque<Cell> open, int x, int y, int z) {
        Cell cell = new Cell(x, y, z);
        if (cells.size() >= MAX_WIDTH * MAX_HEIGHT || cells.contains(cell)
                || !portal(world, x, y, z, axis)) return;
        cells.add(cell);
        open.addLast(cell);
    }

    private static boolean portal(World world, int x, int y, int z, Direction.Axis axis) {
        int id = world.getBlock(x, y, z);
        if (!isPortalState(id)) return false;
        return Blocks.getState(id).get(Properties.HORIZONTAL_AXIS) == axis;
    }

    public static boolean isPortalState(int stateId) {
        return Blocks.getState(stateId).getBlock() == Blocks.getState(Blocks.NETHER_PORTAL).getBlock();
    }

    public static boolean isValidPortal(World world, int x, int y, int z, Direction.Axis axis) {
        Shape shape = find(world, x, y, z, axis, true);
        return shape != null;
    }

    private static Shape find(World world, int x, int y, int z, Direction.Axis axis,
                              boolean requirePortal) {
        if (!interior(world, x, y, z, requirePortal, axis)) return null;
        int bottom = y;
        while (bottom > 1 && interior(world, x, bottom - 1, z, requirePortal, axis)
                && y - bottom < MAX_HEIGHT) bottom--;

        int sx = axis == Direction.Axis.X ? 1 : 0;
        int sz = axis == Direction.Axis.Z ? 1 : 0;
        int minX = x, minZ = z;
        int left = 0;
        while (left <= MAX_WIDTH && interior(world, minX - sx, bottom, minZ - sz,
                requirePortal, axis)) {
            minX -= sx;
            minZ -= sz;
            left++;
        }
        if (!obsidian(world, minX - sx, bottom, minZ - sz)) return null;

        int width = 0;
        while (width <= MAX_WIDTH && interior(world, minX + sx * width, bottom,
                minZ + sz * width, requirePortal, axis)) width++;
        if (width < MIN_WIDTH || width > MAX_WIDTH
                || !obsidian(world, minX + sx * width, bottom, minZ + sz * width)) return null;

        int height = 0;
        for (; height <= MAX_HEIGHT; height++) {
            int rowY = bottom + height;
            boolean allInterior = true;
            for (int w = 0; w < width; w++) {
                if (!interior(world, minX + sx * w, rowY, minZ + sz * w,
                        requirePortal, axis)) {
                    allInterior = false;
                    break;
                }
            }
            if (!allInterior) break;
            if (!obsidian(world, minX - sx, rowY, minZ - sz)
                    || !obsidian(world, minX + sx * width, rowY, minZ + sz * width)) return null;
        }
        if (height < MIN_HEIGHT || height > MAX_HEIGHT) return null;

        for (int w = 0; w < width; w++) {
            int px = minX + sx * w, pz = minZ + sz * w;
            if (!obsidian(world, px, bottom - 1, pz)
                    || !obsidian(world, px, bottom + height, pz)) return null;
        }
        return new Shape(axis, minX, bottom, minZ, width, height);
    }

    private static boolean interior(World world, int x, int y, int z, boolean requirePortal,
                                    Direction.Axis axis) {
        int id = world.getBlock(x, y, z);
        if (id == Blocks.AIR) return !requirePortal;
        if (!isPortalState(id)) return false;
        BlockState state = Blocks.getState(id);
        return state.get(Properties.HORIZONTAL_AXIS) == axis;
    }

    private static boolean obsidian(World world, int x, int y, int z) {
        return world.getBlock(x, y, z) == Blocks.OBSIDIAN;
    }

    private static void forEachInterior(Shape shape, CellConsumer consumer) {
        int sx = shape.axis == Direction.Axis.X ? 1 : 0;
        int sz = shape.axis == Direction.Axis.Z ? 1 : 0;
        for (int h = 0; h < shape.height; h++) {
            for (int w = 0; w < shape.width; w++) {
                consumer.accept(shape.minX + sx * w, shape.bottomY + h, shape.minZ + sz * w);
            }
        }
    }

    @FunctionalInterface
    private interface CellConsumer { void accept(int x, int y, int z); }

    private NetherPortalShape() {}
}
