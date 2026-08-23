package de.skyengine.game.world.block.entity;

import de.skyengine.game.entity.Entity;
import de.skyengine.game.physics.AABB;
import de.skyengine.game.world.block.BlockPos;
import de.skyengine.game.world.block.Blocks;
import de.skyengine.game.world.block.Direction;
import de.skyengine.game.world.block.state.BlockState;
import de.skyengine.game.world.block.state.BlockStateCodec;
import de.skyengine.game.world.block.state.PistonType;
import de.skyengine.game.world.block.state.Properties;
import de.skyengine.game.world.block.shape.BlockShape;

import java.util.ArrayList;
import java.util.List;

/**
 * Der bewegte Block eines Kolben-Schubs (MCs Block 36): hält den transportierten State und
 * gleitet ihn in 2 Game-Ticks (progress +0.5/Tick) in die eigene Zelle. Am Ende wird der
 * State materialisiert; die BE räumt {@code manageBlockEntity} beim setBlock automatisch ab.
 *
 * <p>Das Engine-Off-by-one (eine im scheduledTick von Tick T angelegte BE bekommt noch in T
 * ihr erstes tick()) wird von MCs Struktur „finalisieren erst, wenn lastProgress bereits 1
 * war" absorbiert — es bleiben exakt 2 sichtbare Animations-Ticks.
 *
 * <p>{@code isSource} markiert die Arm-BE des auslösenden Kolbens: beim Ausfahren sitzt sie
 * in der Kopfzelle, beim Einfahren wie in Vanilla in der Basiszelle. Nur sie reiht nach der
 * Materialisierung den Re-Check als Block-Event auf die Basis ein.
 */
public final class PistonMovingBlockEntity extends BlockEntity {

    private static final float STEP = 0.5f;

    /**
     * Impuls, den ein geschobener Slime-Block seinen Passagieren mitgibt (Slime-Werfer).
     *
     * <p><b>Nicht mit {@link #STEP} verwechseln</b> — genau das war hier zuerst falsch: Vanilla
     * setzt in {@code moveCollidedEntities} den reinen Richtungsvektor ({@code Direction.getStepY()}
     * = ±1, per {@code i2d} direkt in die Delta-Movement-Komponente, ohne jede Multiplikation),
     * nicht die Animationsgeschwindigkeit des Kolbens. Der Unterschied ist Faktor 2 auf die
     * Geschwindigkeit und damit Faktor 4 auf die Wurfhöhe.
     */
    private static final double LAUNCH_SPEED = 1.0;

    private int movedStateId = Blocks.AIR;
    private Direction facing = Direction.NORTH;
    private boolean extending = true;
    private boolean isSource;
    /** Klebriger Quell-Kolben? Nur fürs Rendering des Rückzieh-Arms relevant. */
    private boolean sticky;
    private float progress;
    private float lastProgress;
    /**
     * Weltzeit des letzten {@link #tick} (MCs {@code lastTicked}), transient — nach einem Reload
     * zählt einzig der wiederhergestellte {@code progress}. Nur die Drop-Regel liest das Feld,
     * s. {@code PistonBehavior.evaluate}.
     */
    private long lastTicked = -1L;
    /** All moving cells of one piston action, led by the source moving-piston. */
    private long groupLeader = Long.MIN_VALUE;
    private long[] groupMembers = new long[0];

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

    public void configureGroup(long leader, long[] members) {
        this.groupLeader = leader;
        this.groupMembers = members.clone();
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

    /** Weltzeit des letzten Animations-Ticks, {@code -1} solange noch keiner lief. */
    public long getLastTicked() {
        return this.lastTicked;
    }

    /** Interpolierter Fortschritt 0..1 für den Renderer. */
    public float getProgress(float partialTick) {
        return this.lastProgress + (this.progress - this.lastProgress) * partialTick;
    }

    /**
     * Fügt Vanillas dynamische Moving-Piston-Kollisionsform in Weltkoordinaten an.
     * Die technische Blockzelle selbst besitzt absichtlich keine statische Kollisionsform.
     */
    public void appendCollisionBoxes(List<AABB> result) {
        this.appendCollisionBoxes(result, this.pos.x(), this.pos.y(), this.pos.z());
    }

    /** Dynamische Form relativ zur technischen Moving-Piston-Zelle. */
    public BlockShape getCollisionShape() {
        List<AABB> result = new ArrayList<>();
        this.appendCollisionBoxes(result, 0, 0, 0);
        return result.isEmpty() ? BlockShape.EMPTY : new BlockShape(result.toArray(AABB[]::new));
    }

    private void appendCollisionBoxes(List<AABB> result, double baseX, double baseY, double baseZ) {
        double extended = this.extending ? this.progress - 1.0 : 1.0 - this.progress;
        if (this.isSource && !this.extending) {
            BlockState extendedBase = Blocks.getState(this.movedStateId).with(Properties.EXTENDED, true);
            appendStateBoxes(result, extendedBase, baseX, baseY, baseZ);
            BlockState head = Blocks.getState(Blocks.PISTON_HEAD)
                    .with(Properties.FACING_ALL, this.facing)
                    .with(Properties.PISTON_TYPE, this.sticky ? PistonType.STICKY : PistonType.NORMAL)
                    .with(Properties.SHORT, this.progress >= 0.5f);
            appendStateBoxes(result, head,
                    baseX + this.facing.offsetX() * extended,
                    baseY + this.facing.offsetY() * extended,
                    baseZ + this.facing.offsetZ() * extended);
            return;
        }
        BlockState moved = Blocks.getState(this.movedStateId);
        if (this.isSource && moved.getValues().containsKey(Properties.SHORT)) {
            moved = moved.with(Properties.SHORT, this.extending
                    ? this.progress <= 0.5f : this.progress >= 0.5f);
        }
        appendStateBoxes(result, moved,
                baseX + this.facing.offsetX() * extended,
                baseY + this.facing.offsetY() * extended,
                baseZ + this.facing.offsetZ() * extended);
    }

    private static void appendStateBoxes(List<AABB> result, BlockState state,
                                         double x, double y, double z) {
        for (AABB local : state.getCollisionShape().boxes()) {
            result.add(local.copy().move(x, y, z));
        }
    }

    @Override
    public void tick() {
        if (this.world == null) return;
        int x = this.pos.x(), y = this.pos.y(), z = this.pos.z();
        /* Waisen-Regel: passt der Block der Zelle nicht mehr zu uns (inkonsistenter Save,
           weggesprengte Zelle), verschwindet die BE ersatzlos — materialisieren würde
           einen fremden Block überschreiben. */
        if (!isMovingPiston(this.world.getBlock(x, y, z))) {
            this.world.removeBlockEntity(x, y, z);
            return;
        }

        if (this.groupMembers.length > 0) {
            PistonMovingBlockEntity leader = this.groupLeaderEntity();
            if (leader != null && leader != this) return;
            if (leader == this) {
                this.tickGroup();
                return;
            }
            /* Old/incomplete save: never leave a moving-piston orphan frozen forever. */
            this.groupLeader = Long.MIN_VALUE;
            this.groupMembers = new long[0];
        }

        this.lastTicked = this.world.getGameTime();
        this.lastProgress = this.progress;
        if (this.lastProgress >= 1.0f) {
            this.finish(x, y, z);
            return;
        }
        float targetProgress = Math.min(1.0f, this.progress + STEP);
        /* Vanilla bewegt zuerst kollidierende, dann auf Honig stehende Entities und schreibt
           den neuen Fortschritt erst danach in die BlockEntity. */
        this.pushEntities(targetProgress);
        this.moveStuckEntities(targetProgress);
        this.progress = targetProgress;
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
        PistonMovingBlockEntity leader = this.groupLeaderEntity();
        if (leader != null) {
            leader.finishGroup();
            return;
        }
        int x = this.pos.x(), y = this.pos.y(), z = this.pos.z();
        if (!isMovingPiston(this.world.getBlock(x, y, z))) return;
        this.finish(x, y, z);
    }

    private PistonMovingBlockEntity groupLeaderEntity() {
        if (this.world == null || this.groupMembers.length == 0
                || this.groupLeader == Long.MIN_VALUE) return null;
        int x = BlockPos.unpackX(this.groupLeader);
        int y = BlockPos.unpackY(this.groupLeader);
        int z = BlockPos.unpackZ(this.groupLeader);
        if (!(this.world.getBlockEntity(x, y, z) instanceof PistonMovingBlockEntity leader)) {
            return null;
        }
        return leader.groupLeader == this.groupLeader ? leader : null;
    }

    private void tickGroup() {
        List<PistonMovingBlockEntity> members = this.liveGroupMembers();
        if (members.isEmpty()) return;
        long time = this.world.getGameTime();
        float oldProgress = this.progress;
        if (oldProgress >= 1.0f) {
            this.finishGroup();
            return;
        }
        float targetProgress = Math.min(1.0f, oldProgress + STEP);
        for (PistonMovingBlockEntity member : members) {
            member.lastTicked = time;
            member.lastProgress = oldProgress;
            member.progress = oldProgress;
            member.pushEntities(targetProgress);
            member.moveStuckEntities(targetProgress);
        }
        for (PistonMovingBlockEntity member : members) {
            member.progress = targetProgress;
            member.markDirty();
        }
    }

    private List<PistonMovingBlockEntity> liveGroupMembers() {
        List<PistonMovingBlockEntity> result = new ArrayList<>(this.groupMembers.length);
        for (long packed : this.groupMembers) {
            int x = BlockPos.unpackX(packed), y = BlockPos.unpackY(packed), z = BlockPos.unpackZ(packed);
            if (!isMovingPiston(this.world.getBlock(x, y, z))) continue;
            if (this.world.getBlockEntity(x, y, z) instanceof PistonMovingBlockEntity member
                    && member.groupLeader == this.groupLeader) {
                result.add(member);
            }
        }
        return result;
    }

    /** Materialize first, then run observer/neighbor logic over the fully landed structure. */
    private void finishGroup() {
        List<PistonMovingBlockEntity> members = this.liveGroupMembers();
        if (members.isEmpty()) return;

        /* Phase 1 is deliberately free of neighbor updates. No observer may see a half-landed
           slime structure, and no piston counter-edge may finish only one cargo cell. */
        List<PistonMovingBlockEntity> landed = new ArrayList<>(members.size());
        for (PistonMovingBlockEntity member : members) {
            int x = member.pos.x(), y = member.pos.y(), z = member.pos.z();
            if (member.world.setBlock(x, y, z, member.movedStateId, false)) landed.add(member);
        }

        /* All old BlockEntity objects remain valid snapshots after setBlock removed them. */
        for (PistonMovingBlockEntity member : landed) {
            int x = member.pos.x(), y = member.pos.y(), z = member.pos.z();
            Direction movement = member.extending ? member.facing : member.facing.opposite();
            BlockState placed = Blocks.getState(member.movedStateId);
            placed.getBlock().onMovedByPiston(member.world, x, y, z, placed, movement);
        }
        for (PistonMovingBlockEntity member : landed) {
            member.world.updatePistonMovedBlock(member.pos.x(), member.pos.y(), member.pos.z());
        }
        for (PistonMovingBlockEntity member : landed) {
            if (!member.isSource) continue;
            Direction f = member.facing;
            int bx = member.extending ? member.pos.x() - f.offsetX() : member.pos.x();
            int by = member.extending ? member.pos.y() - f.offsetY() : member.pos.y();
            int bz = member.extending ? member.pos.z() - f.offsetZ() : member.pos.z();
            member.world.updateBlockStateAt(bx, by, bz);
        }
    }

    private static boolean isMovingPiston(int stateId) {
        return Blocks.getState(stateId).getBlock()
                == Blocks.getState(Blocks.MOVING_PISTON).getBlock();
    }

    /** Materialisiert den transportierten State und reiht (nur als Source) den Re-Check ein. */
    private void finish(int x, int y, int z) {
        /* setBlock räumt die BE über manageBlockEntity ab (bewegte Blöcke haben nie einen
           BE-Typ — BlockEntity-Blöcke sind piston_reaction=block). false = Chunk gerade
           nicht READY, dann versucht es der nächste Tick erneut. */
        if (!this.world.setBlock(x, y, z, this.movedStateId, false)) return;
        /* Powered-Observer verlieren ihren alten Positions-Tick beim Verschieben. Der Hook
           normalisiert nur diesen Sonderfall; der normale Ankunftspuls entsteht anschliessend
           aus dem gerichteten eigenen Shape-Pass wie in Vanilla. */
        Direction moveDirection = this.extending ? this.facing : this.facing.opposite();
        BlockState placed = Blocks.getState(this.movedStateId);
        placed.getBlock().onMovedByPiston(this.world, x, y, z, placed, moveDirection);
        this.world.updatePistonMovedBlock(x, y, z);
        if (this.isSource) {
            Direction f = this.facing;
            int bx = this.extending ? x - f.offsetX() : x;
            int by = this.extending ? y - f.offsetY() : y;
            int bz = this.extending ? z - f.offsetZ() : z;
            /* Re-Check als Block-Event: Vanillas Event-Drain ist für diesen Tick bereits
               vorbei, deshalb läuft das Event am Anfang des folgenden Weltticks. */
            this.world.updateBlockStateAt(bx, by, bz);
        }
    }

    /** Vanillas {@code moveCollidedEntities}: fegt die echte Kollisionsform über das Tick-Delta. */
    private void pushEntities(float targetProgress) {
        BlockState moved = Blocks.getState(this.movedStateId);
        AABB[] localBoxes = moved.getCollisionShape().boxes();
        if (localBoxes.length == 0) return;
        Direction movement = this.extending ? this.facing : this.facing.opposite();
        double delta = targetProgress - this.progress;
        double extended = this.extending ? this.progress - 1.0 : 1.0 - this.progress;
        double ox = this.pos.x() + this.facing.offsetX() * extended;
        double oy = this.pos.y() + this.facing.offsetY() * extended;
        double oz = this.pos.z() + this.facing.offsetZ() * extended;

        AABB[] movementAreas = new AABB[localBoxes.length];
        AABB bounds = null;
        for (int i = 0; i < localBoxes.length; i++) {
            AABB local = localBoxes[i];
            AABB current = new AABB(local.minX + ox, local.minY + oy, local.minZ + oz,
                    local.maxX + ox, local.maxY + oy, local.maxZ + oz);
            movementAreas[i] = movementArea(current, movement, delta);
            bounds = bounds == null ? union(current, movementAreas[i]) : union(bounds, movementAreas[i]);
        }
        boolean launch = "slime".equals(moved.getBlock().getStickyGroup());
        AABB searchBounds = bounds;
        this.forEachPistonEntity(entity ->
                this.pushOne(entity, searchBounds, movementAreas, movement, delta, launch));
    }

    private void pushOne(Entity entity, AABB searchBounds, AABB[] movementAreas,
                         Direction movement, double delta, boolean launch) {
        if (entity.isRemoved() || !entity.getBoundingBox().intersects(searchBounds)) return;
        /* Vanilla setzt den Slime-Impuls schon für alle Entities in der VoxelShape-Broadphase,
           noch bevor die einzelnen Teilboxen auf echte Überschneidung geprüft werden. */
        if (launch) setAxisMotion(entity, movement, LAUNCH_SPEED);

        double distance = 0.0;
        AABB entityBox = entity.getBoundingBox();
        for (AABB area : movementAreas) {
            if (!area.intersects(entityBox)) continue;
            distance = Math.max(distance, penetration(area, movement, entityBox));
            if (distance >= delta) break;
        }
        if (distance <= 0.0) return;
        double push = Math.min(distance, delta) + 0.01;
        entity.move(this.world, movement.offsetX() * push,
                movement.offsetY() * push, movement.offsetZ() * push);
    }

    /** Vanillas {@code moveStuckEntities}: nur Honig zieht stehende Entities horizontal mit. */
    private void moveStuckEntities(float targetProgress) {
        BlockState moved = Blocks.getState(this.movedStateId);
        if (!"honey".equals(moved.getBlock().getStickyGroup())) return;
        Direction movement = this.extending ? this.facing : this.facing.opposite();
        if (movement.offsetY() != 0) return;

        AABB[] boxes = moved.getCollisionShape().boxes();
        if (boxes.length == 0) return;
        double maxY = 0.0;
        for (AABB box : boxes) maxY = Math.max(maxY, box.maxY);
        double extended = this.extending ? this.progress - 1.0 : 1.0 - this.progress;
        double ox = this.pos.x() + this.facing.offsetX() * extended;
        double oy = this.pos.y() + this.facing.offsetY() * extended;
        double oz = this.pos.z() + this.facing.offsetZ() * extended;
        AABB stickyArea = new AABB(ox, oy + maxY, oz, ox + 1.0, oy + 1.500001, oz + 1.0);
        double delta = targetProgress - this.progress;
        this.forEachPistonEntity(entity -> {
            if (entity.isRemoved() || !entity.onGround
                    || !entity.getBoundingBox().intersects(stickyArea)) return;
            AABB entityBox = entity.getBoundingBox();
            boolean supportedByMovingBlock = Math.abs(entityBox.minY - stickyArea.minY) <= 1.0E-6
                    && entityBox.maxX > stickyArea.minX && entityBox.minX < stickyArea.maxX
                    && entityBox.maxZ > stickyArea.minZ && entityBox.minZ < stickyArea.maxZ;
            boolean footPointInside = entity.x >= stickyArea.minX && entity.x <= stickyArea.maxX
                    && entity.z >= stickyArea.minZ && entity.z <= stickyArea.maxZ;
            if (!supportedByMovingBlock && !footPointInside) return;
            entity.move(this.world, movement.offsetX() * delta, 0.0, movement.offsetZ() * delta);
        });
    }

    private void forEachPistonEntity(java.util.function.Consumer<Entity> action) {
        this.world.forEachEntityNearby(this.pos.x() + 0.5, this.pos.z() + 0.5, 1, action);
        Entity player = this.world.getNearestPlayer(this.pos.x() + 0.5, this.pos.y() + 0.5,
                this.pos.z() + 0.5, 4.0);
        if (player != null) action.accept(player);
    }

    private static AABB movementArea(AABB box, Direction direction, double delta) {
        if (direction.offsetX() > 0) return new AABB(box.maxX, box.minY, box.minZ,
                box.maxX + delta, box.maxY, box.maxZ);
        if (direction.offsetX() < 0) return new AABB(box.minX - delta, box.minY, box.minZ,
                box.minX, box.maxY, box.maxZ);
        if (direction.offsetY() > 0) return new AABB(box.minX, box.maxY, box.minZ,
                box.maxX, box.maxY + delta, box.maxZ);
        if (direction.offsetY() < 0) return new AABB(box.minX, box.minY - delta, box.minZ,
                box.maxX, box.minY, box.maxZ);
        if (direction.offsetZ() > 0) return new AABB(box.minX, box.minY, box.maxZ,
                box.maxX, box.maxY, box.maxZ + delta);
        return new AABB(box.minX, box.minY, box.minZ - delta,
                box.maxX, box.maxY, box.minZ);
    }

    private static double penetration(AABB movementArea, Direction direction, AABB entity) {
        if (direction.offsetX() > 0) return movementArea.maxX - entity.minX;
        if (direction.offsetX() < 0) return entity.maxX - movementArea.minX;
        if (direction.offsetY() > 0) return movementArea.maxY - entity.minY;
        if (direction.offsetY() < 0) return entity.maxY - movementArea.minY;
        if (direction.offsetZ() > 0) return movementArea.maxZ - entity.minZ;
        return entity.maxZ - movementArea.minZ;
    }

    private static AABB union(AABB a, AABB b) {
        return new AABB(Math.min(a.minX, b.minX), Math.min(a.minY, b.minY), Math.min(a.minZ, b.minZ),
                Math.max(a.maxX, b.maxX), Math.max(a.maxY, b.maxY), Math.max(a.maxZ, b.maxZ));
    }

    private static void setAxisMotion(Entity entity, Direction direction, double speed) {
        if (direction.offsetX() != 0) entity.motionX = direction.offsetX() * speed;
        if (direction.offsetY() != 0) entity.motionY = direction.offsetY() * speed;
        if (direction.offsetZ() != 0) entity.motionZ = direction.offsetZ() * speed;
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
        if (this.groupMembers.length > 0) {
            tag.putLong("groupLeader", this.groupLeader);
            tag.putInt("groupSize", this.groupMembers.length);
            for (int i = 0; i < this.groupMembers.length; i++) {
                tag.putLong("group" + i, this.groupMembers[i]);
            }
        }
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
        int groupSize = Math.clamp(tag.getInt("groupSize", 0), 0, 13);
        if (groupSize > 0) {
            this.groupLeader = tag.getLong("groupLeader", Long.MIN_VALUE);
            this.groupMembers = new long[groupSize];
            for (int i = 0; i < groupSize; i++) {
                this.groupMembers[i] = tag.getLong("group" + i, Long.MIN_VALUE);
            }
        } else {
            this.groupLeader = Long.MIN_VALUE;
            this.groupMembers = new long[0];
        }
    }
}
