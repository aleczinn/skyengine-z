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
    private static final double AIR_DRAG = 0.98;
    private static final double EXPLOSION_HEIGHT_FACTOR = 0.0625;
    /**
     * Feste Bodenreibung wie in MC ({@code PrimedTnt.tick}: {@code multiply(0.7, -0.5, 0.7)}).
     * Gezündetes TNT liest <b>absichtlich keinen</b> Block-Reibungswert — es rutscht auf Eis
     * genauso wie auf Stein. Nur Item-Drops nehmen in Vanilla {@code Block.getFriction()}.
     */
    private static final double GROUND_FRICTION = 0.7;
    private static final double GROUND_VERTICAL_BOUNCE = -0.5;

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
        /* Vanilla skaliert zuerst den vollstaendigen Bewegungsvektor mit getAirDrag() und
           wendet erst danach am Boden (0.7, -0.5, 0.7) an. Die Reihenfolge ist fuer TNT auf
           beweglichen/teilhohen Kollisionsformen relevant und darf nicht zusammengezogen werden. */
        this.motionX *= AIR_DRAG;
        this.motionY *= AIR_DRAG;
        this.motionZ *= AIR_DRAG;
        if (this.onGround) {
            this.motionX *= GROUND_FRICTION;
            this.motionY *= GROUND_VERTICAL_BOUNCE;
            this.motionZ *= GROUND_FRICTION;
        }

        if (--this.fuse <= 0) {
            double explosionY = this.explosionY();
            /* Vanilla verwirft die Quell-Entity vor Level.explode; dadurch nimmt sie nicht mehr
               am Entity-Rueckstoss ihrer eigenen Explosion teil. */
            this.remove();
            if (world.getSoundManager() != null) {
                world.getSoundManager().playExplosion(this.x, explosionY, this.z);
            }
            int affectedBlocks = Explosion.explode(world, this.x, explosionY, this.z, this.power);
            world.particles().explosion(this.x, explosionY, this.z, this.power, affectedBlocks);
        } else {
            world.particles().tntFuseSmoke(this.x, this.y + 0.5, this.z);
            /* updateFluidInteraction() liegt in Vanilla nur auf dem noch nicht detonierten Pfad
               und wirkt nach Bewegung/Daempfung auf den naechsten Tick. */
            this.applyFluidPush(world, false, WATER_PUSH);
            this.applyFluidPush(world, true, LAVA_PUSH);
        }
    }

    /**
     * Vanillas {@code PrimedTnt#explode} verwendet {@code getY(0.0625)}: Der Ursprung liegt
     * damit nur ein Sechzehntel der Entity-Hoehe ueber ihrem Fusspunkt, nicht in ihrer Mitte.
     * Genau dieser tiefe Ursprung erzeugt bei TNT-Kanonen den starken Aufwaertsanteil.
     */
    double explosionY() {
        return this.y + this.height * EXPLOSION_HEIGHT_FACTOR;
    }

    /**
     * Weiß-Anteil (0..1) für den Blink beim Rendern — im MC-Takt (alle 5 Ticks wechselnd). Partieller
     * Wert, damit die Textur zwischen den Blitzen erkennbar bleibt.
     */
    public float whiteFlash(float partialTick) {
        return (this.fuse / 5) % 2 == 0 ? 0.6F : 0.0F;
    }
}
