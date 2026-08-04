package de.skyengine.game.world.block.entity;

import de.skyengine.game.entity.Entity;
import de.skyengine.game.physics.AABB;
import de.skyengine.game.world.block.BlockPos;
import de.skyengine.game.world.block.Blocks;
import de.skyengine.game.world.block.Direction;
import de.skyengine.game.world.block.state.BlockState;
import de.skyengine.game.world.block.state.BlockStateCodec;

/**
 * Der bewegte Block eines Kolben-Schubs (MCs Block 36): hält den transportierten State und
 * gleitet ihn in 2 Game-Ticks (progress +0.5/Tick) in die eigene Zelle. Am Ende wird der
 * State materialisiert; die BE räumt {@code manageBlockEntity} beim setBlock automatisch ab.
 *
 * <p>Das Engine-Off-by-one (eine im scheduledTick von Tick T angelegte BE bekommt noch in T
 * ihr erstes tick()) wird von MCs Struktur „finalisieren erst, wenn lastProgress bereits 1
 * war" absorbiert — es bleiben exakt 2 sichtbare Animations-Ticks.
 *
 * <p>{@code isSource} markiert die Arm-BE des auslösenden Kolbens (sitzt einheitlich an der
 * KOPF-Zelle) — nur sie reiht nach der Materialisierung den Re-Check als Block-Event auf die
 * Basis ein (Flicker-Regel: laufende Bewegungen werden nie abgebrochen — bei einer
 * Gegenflanke aber per {@link #finishNow} sofort vollendet, danach wird frisch entschieden).
 */
public final class PistonMovingBlockEntity extends BlockEntity {

    private static final float STEP = 0.5f;

    private int movedStateId = Blocks.AIR;
    private Direction facing = Direction.NORTH;
    private boolean extending = true;
    private boolean isSource;
    /** Klebriger Quell-Kolben? Nur fürs Rendering des Rückzieh-Arms relevant. */
    private boolean sticky;
    private float progress;
    private float lastProgress;

    public PistonMovingBlockEntity(BlockEntityType<?> type, BlockPos pos) {
        super(type, pos);
    }

    /** Konfiguration direkt nach dem setBlock (der Kolben-Code holt sich die frische BE). */
    public void configure(int movedStateId, Direction facing, boolean extending, boolean isSource,
                          boolean sticky) {
        this.movedStateId = movedStateId;
        this.facing = facing;
        this.extending = extending;
        this.isSource = isSource;
        this.sticky = sticky;
        this.progress = 0f;
        this.lastProgress = 0f;
        this.markDirty();
    }

    public int getMovedStateId() {
        return this.movedStateId;
    }

    public Direction getFacing() {
        return this.facing;
    }

    public boolean isExtending() {
        return this.extending;
    }

    public boolean isSource() {
        return this.isSource;
    }

    public boolean isSticky() {
        return this.sticky;
    }

    /** Interpolierter Fortschritt 0..1 für den Renderer. */
    public float getProgress(float partialTick) {
        return this.lastProgress + (this.progress - this.lastProgress) * partialTick;
    }

    @Override
    public void tick() {
        if (this.world == null) return;
        int x = this.pos.x(), y = this.pos.y(), z = this.pos.z();
        /* Waisen-Regel: passt der Block der Zelle nicht mehr zu uns (inkonsistenter Save,
           weggesprengte Zelle), verschwindet die BE ersatzlos — materialisieren würde
           einen fremden Block überschreiben. */
        if (this.world.getBlock(x, y, z) != Blocks.MOVING_PISTON) {
            this.world.removeBlockEntity(x, y, z);
            return;
        }

        this.lastProgress = this.progress;
        if (this.lastProgress >= 1.0f) {
            this.finish(x, y, z);
            return;
        }
        this.progress = Math.min(1.0f, this.progress + STEP);
        this.pushEntities(this.progress - this.lastProgress);
        this.markDirty();
    }

    /**
     * Fast-Forward von außen (Kolben-Gegenflanke, s. {@code PistonBehavior.evaluate}): vollendet
     * die Bewegung sofort, statt sie abzubrechen. Waisen-Schutz wie in {@link #tick}; nach dem
     * finish räumt setBlock die BE ab — ein späterer tick() derselben Instanz griffe ins Leere
     * und würde sich über die Waisen-Regel selbst entfernen.
     */
    public void finishNow() {
        if (this.world == null) return;
        int x = this.pos.x(), y = this.pos.y(), z = this.pos.z();
        if (this.world.getBlock(x, y, z) != Blocks.MOVING_PISTON) return;
        this.finish(x, y, z);
    }

    /** Materialisiert den transportierten State und reiht (nur als Source) den Re-Check ein. */
    private void finish(int x, int y, int z) {
        /* setBlock räumt die BE über manageBlockEntity ab (bewegte Blöcke haben nie einen
           BE-Typ — BlockEntity-Blöcke sind piston_reaction=block). false = Chunk gerade
           nicht READY, dann versucht es der nächste Tick erneut. */
        if (!this.world.setBlock(x, y, z, this.movedStateId, false)) return;
        /* VOR dem Nachbar-Ring: dessen erster Schritt ist updateStateAt auf diese Zelle selbst,
           und genau der soll den frisch abgesetzten Zustand schon kennen (ein verschobener
           Beobachter pulst dadurch, statt stumm anzukommen). */
        Direction moveDirection = this.extending ? this.facing : this.facing.opposite();
        BlockState placed = Blocks.getState(this.movedStateId);
        placed.getBlock().onMovedByPiston(this.world, x, y, z, placed, moveDirection);
        this.world.updateNeighbors(x, y, z);
        if (this.isSource) {
            /* Source-BEs sitzen einheitlich an der KOPF-Zelle, die Basis liegt dahinter. */
            Direction f = this.facing;
            int bx = x - f.offsetX(), by = y - f.offsetY(), bz = z - f.offsetZ();
            if (!this.extending) {
                /* Retract: die Basis blieb während der Animation ein echter
                   piston[extended=true]-Block (Chunk-Licht statt BE-Flat-Licht — sonst
                   blitzte der ganze Würfel auf) und wird erst JETZT eingefahren. */
                BlockState base = Blocks.getState(this.world.getBlock(bx, by, bz));
                if (base.getValues().containsKey(de.skyengine.game.world.block.state.Properties.EXTENDED)
                        && base.get(de.skyengine.game.world.block.state.Properties.EXTENDED)
                        && base.get(de.skyengine.game.world.block.state.Properties.FACING_ALL) == f) {
                    this.world.setBlock(bx, by, bz,
                            base.with(de.skyengine.game.world.block.state.Properties.EXTENDED, false).getId(), true);
                }
            }
            /* Re-Check als Block-Event: läuft noch im SELBEN Tick (Drain B) und kann nicht
               im First-wins-Dedup des Tick-Schedulers hängen bleiben. */
            this.world.enqueueBlockEvent(bx, by, bz);
        }
    }

    /**
     * Schiebt Entities vor dem gleitenden Block her: alle, deren BoundingBox die aktuelle
     * Block-Box schneidet, werden per {@code Entity.move} (mit Kollision) um das Tick-Delta
     * in Bewegungsrichtung versetzt.
     *
     * <p><b>Slime-Launcher</b> (MC): ist der bewegte Block ein Slime (sticky_group "slime"),
     * bekommt die Entity zusätzlich die Kolben-Geschwindigkeit als Motion auf der
     * Bewegungsachse — sie behält den Impuls nach dem Stopp und fliegt weiter
     * (Slime-Werfer). Honig launcht wie in MC nicht.
     */
    private void pushEntities(float delta) {
        /* Der nicht-klebrige Rückzieh-Arm transportiert nur Luft (reine Renderer-Optik) —
           seine unsichtbare Box darf niemanden Richtung Kolben schieben. */
        if (this.movedStateId == Blocks.AIR) return;
        Direction d = this.extending ? this.facing : this.facing.opposite();
        double back = 1.0 - this.progress;
        double bx = this.pos.x() - d.offsetX() * back;
        double by = this.pos.y() - d.offsetY() * back;
        double bz = this.pos.z() - d.offsetZ() * back;
        AABB box = new AABB(bx, by, bz, bx + 1, by + 1, bz + 1);
        boolean launch = "slime".equals(Blocks.getState(this.movedStateId).getBlock().getStickyGroup());
        this.world.forEachEntityNearby(this.pos.x() + 0.5, this.pos.z() + 0.5, 1, entity -> {
            if (entity.isRemoved() || !entity.getBoundingBox().intersects(box)) return;
            entity.move(this.world, d.offsetX() * delta, d.offsetY() * delta, d.offsetZ() * delta);
            if (launch) {
                /* Kolben-Geschwindigkeit = STEP Blöcke/Tick; nur die Bewegungsachse wird
                   ersetzt (MC-Semantik), Quer-Motion bleibt erhalten. */
                if (d.offsetX() != 0) entity.motionX = d.offsetX() * STEP;
                if (d.offsetY() != 0) entity.motionY = d.offsetY() * STEP;
                if (d.offsetZ() != 0) entity.motionZ = d.offsetZ() * STEP;
            }
        });
    }

    @Override
    public void save(DataTag tag) {
        /* State als Codec-String, nie als Runtime-ID — die ist flüchtig. */
        tag.putString("state", BlockStateCodec.encode(Blocks.getState(this.movedStateId)));
        tag.putInt("facing", this.facing.ordinal());
        tag.putBoolean("extending", this.extending);
        tag.putBoolean("source", this.isSource);
        tag.putBoolean("sticky", this.sticky);
        tag.putDouble("progress", this.progress);
    }

    @Override
    public void load(DataTag tag) {
        BlockState state = BlockStateCodec.decode(tag.getString("state", ""));
        this.movedStateId = state != null ? state.getId() : Blocks.AIR;
        int ordinal = tag.getInt("facing", Direction.NORTH.ordinal());
        Direction[] dirs = Direction.values();
        this.facing = dirs[Math.clamp(ordinal, 0, dirs.length - 1)];
        this.extending = tag.getBoolean("extending", true);
        this.isSource = tag.getBoolean("source", false);
        this.sticky = tag.getBoolean("sticky", false);
        this.progress = (float) tag.getDouble("progress", 0.0);
        this.lastProgress = this.progress;
    }
}
