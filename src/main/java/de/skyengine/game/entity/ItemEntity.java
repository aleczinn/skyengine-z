package de.skyengine.game.entity;

import de.skyengine.game.physics.AABB;
import de.skyengine.game.world.World;
import de.skyengine.game.world.item.ItemStack;

import java.util.function.Consumer;

/**
 * Ein in der Welt liegendes/aufsammelbares Item (Block-Drop). Fällt mit Schwerkraft, bleibt am
 * Boden liegen und wird vom Spieler aufgesammelt (siehe {@code GameContainer}). {@link #age} treibt
 * im Renderer die Dreh-/Wippe-Animation und begrenzt die Lebensdauer; {@link #pickupDelay}
 * verhindert sofortiges Aufsammeln nach dem Drop.
 *
 * <p>Benachbarte gleiche Stapel verschmelzen pro Tick ({@link #mergeNearby}), damit aus einer
 * Explosion keine Wolke einzelner Entities stehen bleibt.
 */
public class ItemEntity extends Entity {

    private static final double GRAVITY = 0.04;
    private static final double DRAG_Y = 0.98;
    private static final double AIR_FRICTION = 0.98;
    /** Lebensdauer eines Drops in Ticks (MC: 6000 = 5 Minuten). */
    private static final int DESPAWN_TICKS = 6000;
    /** Horizontaler Suchradius fürs Verschmelzen (MC bläht die Hitbox um 0,5 auf). */
    private static final double MERGE_RADIUS = 0.5;
    /** Wiederverwendete Suchbox — sonst fiele pro Item und Tick eine AABB an. */
    private static final AABB MERGE_RANGE = new AABB(0, 0, 0, 0, 0, 0);

    private final ItemStack stack;
    /** Einmal gebaut statt pro Tick — der Merge-Besuch läuft jeden Tick über alle Nachbarn. */
    private final Consumer<Entity> mergeVisitor = this::tryMerge;
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

    /** Sperre bis zum Aufsammeln in Ticks (MC: 40 beim Spieler-Wurf, sonst bleibt der Default). */
    public void setPickupDelay(int ticks) {
        this.pickupDelay = Math.max(0, ticks);
    }

    @Override
    public void tick(World world) {
        super.update();
        this.age++;
        /* Nach 5 Minuten verschwindet der Drop (wie MC). Ausserhalb der Simulations-Distanz wird
           gar nicht getickt, dort altert er also nicht weiter — auch das ist MC-Verhalten. */
        if (this.age >= DESPAWN_TICKS) {
            this.remove();
            return;
        }
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

        /* Bodenreibung kommt aus dem Block (MCs ItemEntity.tick multipliziert die Reibung des
           Blocks unter sich mit dem Luftwiderstand) — deshalb rutscht ein Drop auf Eis weit und
           auf Stein kaum. Normalboden ergibt 0.6 * 0.98; eine feste Konstante wäre hier schon
           ohne Eis leicht daneben. */
        double friction = this.onGround
                ? this.blockBelow(world).getFriction() * AIR_FRICTION
                : AIR_FRICTION;
        this.motionX *= friction;
        this.motionZ *= friction;

        this.mergeNearby(world);
    }

    /**
     * Legt benachbarte gleiche Stapel zusammen (Vorbild MC {@code ItemEntity.mergeWithNeighbours}).
     * Senkt die Entity-Zahl spürbar, etwa nach einer Explosion.
     *
     * <p>Läuft wie in MC jeden Tick — die Kostenbremse ist {@link #isMergeable()}: sobald ein
     * Stapel voll ist, fragt er gar nicht mehr nach Nachbarn. Verändert wird ausschließlich, was
     * der Vertrag von {@code forEachEntityNearby} erlaubt: Zähler umbuchen und das removed-Flag
     * setzen. Aus den Chunk-Listen räumt erst {@code reconcileEntityChunks} nach dem Tick.
     */
    private void mergeNearby(World world) {
        if (!this.isMergeable()) return;
        MERGE_RANGE.set(this.boundingBox.minX, this.boundingBox.minY, this.boundingBox.minZ,
                        this.boundingBox.maxX, this.boundingBox.maxY, this.boundingBox.maxZ)
                .inflate(MERGE_RADIUS, 0, MERGE_RADIUS);
        world.forEachEntityNearby(this.x, this.z, 1, this.mergeVisitor);
    }

    /** Ein Nachbar aus {@link #mergeNearby}; die Suchbox steht in {@link #MERGE_RANGE}. */
    private void tryMerge(Entity other) {
        if (other == this || !(other instanceof ItemEntity o)) return;
        /* Beide Seiten neu prüfen: this kann in dieser Schleife schon leer geworden sein. */
        if (!this.isMergeable() || !o.isMergeable()) return;
        if (!o.canMergeWith(this.stack)) return;
        if (!MERGE_RANGE.intersects(o.getBoundingBox())) return;

        /* Wie in MC bekommt der GRÖSSERE Stapel dazu — sonst schieben zwei Entities hin und her. */
        if (this.stack.getCount() >= o.stack.getCount()) {
            this.absorb(o);
        } else {
            o.absorb(this);
        }
    }

    /** Nimmt so viel wie möglich aus {@code source} auf; leert der dabei, verschwindet er. */
    private void absorb(ItemEntity source) {
        int moved = Math.min(this.stack.getMaxStackSize() - this.stack.getCount(), source.stack.getCount());
        if (moved <= 0) return;
        this.stack.setCount(this.stack.getCount() + moved);
        source.stack.setCount(source.stack.getCount() - moved);

        /* Sperre = Maximum: ein frisch geworfenes Item darf nicht dadurch sofort wieder
           aufsammelbar werden, dass es in einen alten Haufen wandert. Alter = Minimum, damit ein
           Haufen nicht am ältesten Beitrag despawnt. */
        this.pickupDelay = Math.max(this.pickupDelay, source.pickupDelay);
        this.age = Math.min(this.age, source.age);
        if (source.stack.isEmpty()) source.remove();
    }

    /** Noch aufnahmefähig? Voll oder leer heißt: gar nicht erst nach Nachbarn suchen. */
    private boolean isMergeable() {
        return !this.isRemoved() && !this.stack.isEmpty()
                && this.stack.getCount() < this.stack.getMaxStackSize();
    }

    /**
     * Passt der eigene Stapel zu {@code other}? Neben dem Item-Typ zählt die Abnutzung —
     * {@link ItemStack#canStackWith} lässt sie bewusst außen vor, hier darf sie nicht wegfallen.
     */
    private boolean canMergeWith(ItemStack other) {
        return this.stack.canStackWith(other) && this.stack.getDamage() == other.getDamage();
    }
}
