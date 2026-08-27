package de.skyengine.game.entity;

import de.skyengine.game.physics.AABB;
import de.skyengine.game.world.Dimension;
import de.skyengine.game.world.block.Blocks;
import de.skyengine.game.world.block.Direction;
import de.skyengine.game.world.block.state.BlockState;
import de.skyengine.game.world.block.state.Properties;
import de.skyengine.game.world.item.ItemStack;
import de.skyengine.game.world.item.Items;

/**
 * Vanillas normales Item Frame als ortsfeste Hanging-Entity. {@code anchor*} ist die Luftzelle,
 * in der der Rahmen haengt; {@link #direction} zeigt von seinem Stuetzblock nach aussen.
 */
public final class ItemFrameEntity extends Entity {

    private static final double CENTER_OFFSET = 0.46875;
    private static final double DEPTH = 0.0625;
    private static final double SIZE = 0.75;
    private static final int SURVIVAL_CHECK_INTERVAL = 100;

    private final int anchorX, anchorY, anchorZ;
    private final Direction direction;
    private ItemStack item = ItemStack.EMPTY;
    private int rotation;
    private int survivalCheck;

    public ItemFrameEntity(int anchorX, int anchorY, int anchorZ, Direction direction) {
        this.anchorX = anchorX;
        this.anchorY = anchorY;
        this.anchorZ = anchorZ;
        this.direction = direction;
        this.setPosition(
                anchorX + 0.5 - direction.offsetX() * CENTER_OFFSET,
                anchorY + 0.5 - direction.offsetY() * CENTER_OFFSET,
                anchorZ + 0.5 - direction.offsetZ() * CENTER_OFFSET);
    }

    public int getAnchorX() { return this.anchorX; }
    public int getAnchorY() { return this.anchorY; }
    public int getAnchorZ() { return this.anchorZ; }
    public Direction getDirection() { return this.direction; }
    public ItemStack getItem() { return this.item; }
    public int getRotation() { return this.rotation; }

    @Override
    public boolean isPersistent() { return true; }

    @Override
    protected void updateBoundingBox() {
        double sx = this.direction.axis() == Direction.Axis.X ? DEPTH : SIZE;
        double sy = this.direction.axis() == Direction.Axis.Y ? DEPTH : SIZE;
        double sz = this.direction.axis() == Direction.Axis.Z ? DEPTH : SIZE;
        this.boundingBox.set(this.x - sx * 0.5, this.y - sy * 0.5, this.z - sz * 0.5,
                this.x + sx * 0.5, this.y + sy * 0.5, this.z + sz * 0.5);
    }

    @Override
    public void tick(Dimension world) {
        super.update();
        if (++this.survivalCheck >= SURVIVAL_CHECK_INTERVAL) {
            this.survivalCheck = 0;
            if (!this.survives(world)) this.breakNaturally(world);
        }
    }

    /** Vanilla: Vollblock, bei horizontalem Rahmen ausserdem Repeater/Comparator als Stuetzblock. */
    public boolean hasValidSupport(Dimension world) {
        int sx = this.anchorX - this.direction.offsetX();
        int sy = this.anchorY - this.direction.offsetY();
        int sz = this.anchorZ - this.direction.offsetZ();
        BlockState support = Blocks.getState(world.getBlock(sx, sy, sz));
        if (support.isSolid()) return true;
        return this.direction.axis() != Direction.Axis.Y
                && (support.getValues().containsKey(Properties.DELAY)
                    || support.getValues().containsKey(Properties.MODE));
    }

    public boolean survives(Dimension world) {
        if (!this.hasValidSupport(world)) return false;
        for (AABB collision : world.getCollisionBoxes(this.boundingBox)) {
            if (collision.intersects(this.boundingBox)) return false;
        }
        return !world.hasOverlappingItemFrame(this);
    }

    /**
     * Vanillas {@code HangingEntity.canCoexist(true)} fuer Item Frames: Nur ein Rahmen mit
     * derselben Anhefterichtung blockiert diese Flaeche. Rechtwinklig ausgerichtete Rahmen
     * duerfen selbst dann koexistieren, wenn ihre Bounding-Boxes einander schneiden.
     */
    public boolean conflictsWith(ItemFrameEntity other) {
        return other != this && !other.isRemoved() && other.direction == this.direction
                && other.boundingBox.intersects(this.boundingBox);
    }

    /** Rechtsklick: erst ein Exemplar einsetzen, danach in acht 45-Grad-Schritten drehen. */
    public boolean interact(Dimension world, ItemStack held, boolean creative) {
        if (this.isRemoved()) return false;
        if (this.item.isEmpty()) {
            if (held == null || held.isEmpty()) return false;
            ItemStack inserted = held.copy();
            inserted.setCount(1);
            this.item = inserted;
            if (!creative) held.setCount(held.getCount() - 1);
        } else {
            this.rotation = (this.rotation + 1) & 7;
        }
        this.changed(world);
        return true;
    }

    /** Erster Schlag entfernt den Inhalt, erst der Schlag auf den leeren Rahmen den Rahmen selbst. */
    public void attack(Dimension world, boolean creative) {
        if (this.isRemoved()) return;
        if (!this.item.isEmpty()) {
            if (!creative) world.spawnItem(this.x, this.y, this.z, this.item.copy());
            this.item = ItemStack.EMPTY;
            if (world.getSoundManager() != null) {
                world.getSoundManager().playItemFrameRemoveItem(this.x, this.y, this.z);
            }
            /* Vanilla setzt beim Entfernen nur DATA_ITEM auf leer: DATA_ROTATION bleibt erhalten.
               Ein spaeter eingesetztes Item erscheint deshalb wieder in derselben Drehung. */
            this.changed(world);
            return;
        }
        this.remove();
        if (world.getSoundManager() != null) {
            world.getSoundManager().playItemFrameBreak(this.x, this.y, this.z);
        }
        if (!creative) world.spawnItem(this.x, this.y, this.z,
                new ItemStack(Items.get(de.skyengine.game.world.block.Identifier.of("skyengine:item_frame")), 1));
        this.changed(world);
    }

    public void breakNaturally(Dimension world) {
        if (this.isRemoved()) return;
        if (world.getSoundManager() != null) {
            world.getSoundManager().playItemFrameBreak(this.x, this.y, this.z);
        }
        if (!this.item.isEmpty()) world.spawnItem(this.x, this.y, this.z, this.item.copy());
        world.spawnItem(this.x, this.y, this.z,
                new ItemStack(Items.get(de.skyengine.game.world.block.Identifier.of("skyengine:item_frame")), 1));
        this.item = ItemStack.EMPTY;
        this.remove();
        this.changed(world);
    }

    public int getAnalogOutput() {
        return this.item.isEmpty() ? 0 : this.rotation + 1;
    }

    public ItemStack getPickResult() {
        if (!this.item.isEmpty()) return this.item.copy();
        return new ItemStack(Items.get(de.skyengine.game.world.block.Identifier.of("skyengine:item_frame")), 1);
    }

    public void loadContent(ItemStack item, int rotation) {
        this.item = item == null ? ItemStack.EMPTY : item;
        this.rotation = Math.floorMod(rotation, 8);
    }

    /** Entfernung entlang eines Strahls oder +unendlich bei keinem Treffer. */
    public double rayIntersection(double ox, double oy, double oz, double dx, double dy, double dz,
                                  double maxDistance) {
        double near = 0.0;
        double far = maxDistance;
        double[] origins = {ox, oy, oz};
        double[] directions = {dx, dy, dz};
        double[] mins = {this.boundingBox.minX, this.boundingBox.minY, this.boundingBox.minZ};
        double[] maxs = {this.boundingBox.maxX, this.boundingBox.maxY, this.boundingBox.maxZ};
        for (int axis = 0; axis < 3; axis++) {
            if (Math.abs(directions[axis]) < 1.0E-9) {
                if (origins[axis] < mins[axis] || origins[axis] > maxs[axis]) return Double.POSITIVE_INFINITY;
                continue;
            }
            double a = (mins[axis] - origins[axis]) / directions[axis];
            double b = (maxs[axis] - origins[axis]) / directions[axis];
            if (a > b) { double swap = a; a = b; b = swap; }
            near = Math.max(near, a);
            far = Math.min(far, b);
            if (near > far) return Double.POSITIVE_INFINITY;
        }
        return near <= maxDistance ? near : Double.POSITIVE_INFINITY;
    }

    private void changed(Dimension world) {
        world.markChunkModified(this.anchorX, this.anchorZ);
        world.updateComparatorOutputs(this.anchorX, this.anchorY, this.anchorZ);
    }
}
