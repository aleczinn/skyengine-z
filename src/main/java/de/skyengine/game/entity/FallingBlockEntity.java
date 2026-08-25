package de.skyengine.game.entity;

import de.skyengine.game.world.Dimension;
import de.skyengine.game.world.block.Blocks;
import de.skyengine.game.world.block.state.BlockState;
import de.skyengine.game.world.item.Item;
import de.skyengine.game.world.item.ItemStack;
import de.skyengine.game.world.item.Items;
import de.skyengine.game.world.loot.LootContext;

/**
 * Ein flüssig fallender Block (Sand, Kies). Wird von {@link
 * de.skyengine.game.world.block.behavior.GravityBehavior} gespawnt, sobald der Untergrund wegfällt,
 * und beim Aufprall wieder zu einem Block. Ersetzt das ruckartige block-basierte Fallen.
 */
public class FallingBlockEntity extends Entity {

    private static final double GRAVITY = 0.04;     // Beschleunigung pro Tick (wie MC)
    private static final double DRAG_Y = 0.98;

    /**
     * Der fallende State (als gebackene Runtime-ID).
     */
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
    public void tick(Dimension world) {
        super.update();

        this.motionY -= GRAVITY;
        this.move(world, 0, this.motionY, 0);
        this.motionY *= DRAG_Y;

        if (this.onGround) {
            int bx = (int) Math.floor(this.x);
            int by = (int) Math.floor(this.y);
            int bz = (int) Math.floor(this.z);
            world.particles().landing(this.x, this.y, this.z,
                    Blocks.getState(this.blockId), 2.0F);

            /* Zielzelle frei oder Fluid -> wieder Block werden (verdrängt das Fluid wie in MC);
               sonst (z.B. dort steht inzwischen etwas Festes) als Item droppen. */
            if (Blocks.canFallInto(world.getBlock(bx, by, bz))) {
                /* Zielchunk nicht READY (Ladefront/Chunkgrenze): liegen bleiben und im
                   nächsten Tick erneut versuchen — der Block verschwand sonst ersatzlos. */
                if (!world.setBlock(bx, by, bz, this.blockId)) return;
            } else {
                this.dropAsItem(world, bx, by, bz);
            }
            this.remove();
        }
    }

    private void dropAsItem(Dimension world, int bx, int by, int bz) {
        BlockState state = Blocks.getState(this.blockId);
        world.dropBlockLoot(bx, by, bz, state, LootContext.Cause.SUPPORT);
    }
}
