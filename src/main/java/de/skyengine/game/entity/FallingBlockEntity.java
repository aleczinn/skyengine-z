package de.skyengine.game.entity;

import de.skyengine.game.world.World;
import de.skyengine.game.world.block.Blocks;
import de.skyengine.game.world.block.state.BlockState;
import de.skyengine.game.world.item.Item;
import de.skyengine.game.world.item.ItemStack;
import de.skyengine.game.world.item.Items;

/**
 * Ein flüssig fallender Block (Sand, Kies). Wird von {@link
 * de.skyengine.game.world.block.behavior.GravityBehavior} gespawnt, sobald der Untergrund wegfällt,
 * und beim Aufprall wieder zu einem Block. Ersetzt das ruckartige block-basierte Fallen.
 */
public class FallingBlockEntity extends Entity {

    private static final double GRAVITY = 0.04;     // Beschleunigung pro Tick (wie MC)
    private static final double DRAG_Y = 0.98;

    /** Der fallende State (als gebackene Runtime-ID). */
    private final int blockId;

    public FallingBlockEntity(int blockId) {
        this.blockId = blockId;
        /* Knapp unter 1, damit die Box sauber in eine Zelle rastet (kein Klemmen an Nachbarn). */
        this.setSize(0.98F, 0.98F);
    }

    public int getBlockId() {
        return this.blockId;
    }

    @Override
    public boolean isCollidable() {
        return true;   // belegt die Zelle -> man kann keinen Block hineinsetzen, solange er fällt
    }

    @Override
    public void tick(World world) {
        super.update();

        this.motionY -= GRAVITY;
        this.move(world, 0, this.motionY, 0);
        this.motionY *= DRAG_Y;

        if (this.onGround) {
            int bx = (int) Math.floor(this.x);
            int by = (int) Math.floor(this.y);
            int bz = (int) Math.floor(this.z);

            /* Zielzelle frei oder Fluid -> wieder Block werden (verdrängt das Fluid wie in MC);
               sonst (z.B. dort steht inzwischen etwas Festes) als Item droppen. */
            if (Blocks.canFallInto(world.getBlock(bx, by, bz))) {
                world.setBlock(bx, by, bz, this.blockId);
            } else {
                this.dropAsItem(world, bx, by, bz);
            }
            this.remove();
        }
    }

    private void dropAsItem(World world, int bx, int by, int bz) {
        BlockState state = Blocks.getState(this.blockId);
        Item item = Items.get(state.getBlock().getIdentifier());
        if (item != null) {
            world.spawnItem(bx + 0.5, by + 0.5, bz + 0.5, new ItemStack(item, 1));
        }
    }
}
