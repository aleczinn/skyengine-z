package de.skyengine.game.entity;

import de.skyengine.game.world.Explosion;
import de.skyengine.game.world.World;

/**
 * Gezündetes TNT als Entity (wie Minecraft): fällt/hüpft mit einem Fuse-Countdown, blinkt beim
 * Rendern weiß auf und detoniert am Ende über {@link Explosion#explode}. Gespawnt von {@link
 * de.skyengine.game.world.World#spawnPrimedTnt} (aus {@link
 * de.skyengine.game.world.block.behavior.ExplosionBehavior} beim Rechtsklick bzw. aus der
 * Kettenreaktion in {@link Explosion}).
 *
 * <p>Physik-Muster wie {@link FallingBlockEntity}/{@link ItemEntity} (Gravitation + achsenweise
 * Kollision über {@link Entity#move}); nicht kollidierbar, damit es den Spieler nicht blockiert.
 */
public class PrimedTntEntity extends Entity {

    private static final double GRAVITY = 0.04;
    private static final double DRAG_Y = 0.98;
    private static final double GROUND_FRICTION = 0.6;
    private static final double AIR_FRICTION = 0.98;

    private final float power;
    private int fuse;

    public PrimedTntEntity(float power, int fuse) {
        this.power = power;
        this.fuse = fuse;
        /* Knapp unter 1, damit die Box sauber in einer Zelle liegt (wie FallingBlockEntity). */
        this.setSize(0.98F, 0.98F);
    }

    @Override
    public void tick(World world) {
        super.update();

        this.motionY -= GRAVITY;
        this.move(world, this.motionX, this.motionY, this.motionZ);
        this.motionY *= DRAG_Y;
        double friction = this.onGround ? GROUND_FRICTION : AIR_FRICTION;
        this.motionX *= friction;
        this.motionZ *= friction;

        /* Strömung trägt gezündetes TNT mit — MCs PrimedTnt.tick ruft dafür am Ende
           updateFluidInteraction(), und das macht ausschließlich diesen Push (kein Auftrieb,
           keine eigene Wasser-Physik). Reihenfolge wie dort: erst bewegen und dämpfen, dann
           schieben, damit der Schub im nächsten Tick wirkt. */
        this.applyFluidPush(world, false, WATER_PUSH);
        this.applyFluidPush(world, true, LAVA_PUSH);

        if (--this.fuse <= 0) {
            if (world.getSoundManager() != null) {
                world.getSoundManager().playExplosion(this.x, this.y + 0.5, this.z);
            }
            Explosion.explode(world, this.x, this.y + 0.5, this.z, this.power);
            this.remove();
        }
    }

    /**
     * Weiß-Anteil (0..1) für den Blink beim Rendern — im MC-Takt (alle 5 Ticks wechselnd). Partieller
     * Wert, damit die Textur zwischen den Blitzen erkennbar bleibt.
     */
    public float whiteFlash(float partialTick) {
        return (this.fuse / 5) % 2 == 0 ? 0.6F : 0.0F;
    }
}
