package de.skyengine.game.entity;

import de.skyengine.game.world.World;
import de.skyengine.game.world.item.ItemStack;

/**
 * Ein in der Welt liegendes/aufsammelbares Item (Block-Drop). Fällt mit Schwerkraft, bleibt am
 * Boden liegen und wird vom Spieler aufgesammelt (siehe {@code GameContainer}). {@link #age} treibt
 * im Renderer die Dreh-/Wippe-Animation; {@link #pickupDelay} verhindert sofortiges Aufsammeln nach
 * dem Drop.
 */
public class ItemEntity extends Entity {

    private static final double GRAVITY = 0.04;
    private static final double DRAG_Y = 0.98;
    private static final double GROUND_FRICTION = 0.6;
    private static final double AIR_FRICTION = 0.98;

    private final ItemStack stack;
    private int pickupDelay = 10;   // ~0,5 s kein Aufsammeln (wie MC)
    private int age;

    public ItemEntity(ItemStack stack) {
        this.stack = stack;
        this.setSize(0.25F, 0.25F);
    }

    public ItemStack getStack() {
        return this.stack;
    }

    public int getAge() {
        return this.age;
    }

    public int getPickupDelay() {
        return this.pickupDelay;
    }

    @Override
    public void tick(World world) {
        super.update();
        this.age++;
        if (this.pickupDelay > 0) this.pickupDelay--;

        /* Strömung zieht das Item mit (Lava: nur Push - Items verbrennen bei uns nicht). */
        this.applyFluidPush(world, false, WATER_PUSH);
        this.applyFluidPush(world, true, LAVA_PUSH);

        if (this.isInFluid(world, false)) {
            /* Unter Wasser: keine Gravitation, sanfter Auftrieb (Vanilla setUnderwaterMovement);
               die normale Reibung unten läuft zusätzlich (wie in Minecraft). */
            this.motionX *= 0.99;
            this.motionZ *= 0.99;
            if (this.motionY < 0.06) this.motionY += 5.0E-4;
        } else {
            this.motionY -= GRAVITY;
        }
        this.move(world, this.motionX, this.motionY, this.motionZ);
        this.motionY *= DRAG_Y;

        double friction = this.onGround ? GROUND_FRICTION : AIR_FRICTION;
        this.motionX *= friction;
        this.motionZ *= friction;
    }
}
