package de.skyengine.game.entity;

import de.skyengine.game.world.World;
import de.skyengine.game.world.block.Direction;
import de.skyengine.game.world.block.behavior.RailBehavior;
import de.skyengine.game.world.block.state.BlockState;
import de.skyengine.game.world.block.state.Properties;
import de.skyengine.game.world.block.state.RailShape;
import de.skyengine.game.physics.AABB;

import java.util.ArrayList;
import java.util.List;

/** Fahrbares Standard-Minecart mit Vanilla-naher Schienenprojektion und Antriebsschienen-Physik. */
public final class MinecartEntity extends Entity {

    private static final double GRAVITY = 0.04;
    private static final double MAX_RAIL_SPEED = 0.4;
    private static final double SLOPE_ACCELERATION = 0.0078125;
    private static final double POWERED_ACCELERATION = 0.06;

    private double passengerImpulseX;
    private double passengerImpulseZ;
    private float previousYaw;
    private float previousPitch;
    private float damage;
    private int hurtTime;
    private int hurtDirection = 1;

    public MinecartEntity() {
        this.setSize(0.98F, 0.7F);
    }

    /**
     * Richtet ein frisch erzeugtes, noch stehendes Minecart an der lokalen Schienentangente aus.
     * Die normale Tick-Rotation benutzt die Bewegung; ohne diesen Initialwert behielt ein neu
     * platziertes Cart bis zum ersten Anrollen den Entity-Default und stand quer auf Ost-West-
     * Schienen beziehungsweise gerade in Kurven.
     */
    public void alignToRail(World world) {
        RailPosition rail = this.findRail(world);
        if (rail == null) return;
        Segment segment = segment(rail.x, rail.y, rail.z, RailBehavior.shape(rail.state));
        double dx = segment.x1 - segment.x0;
        double dy = segment.y1 - segment.y0;
        double dz = segment.z1 - segment.z0;
        this.yaw = (float) Math.toDegrees(Math.atan2(dx, -dz));
        this.pitch = (float) Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
        this.previousYaw = this.yaw;
        this.previousPitch = this.pitch;
    }

    @Override
    public boolean isCollidable() {
        return true;
    }

    @Override
    public boolean isPersistent() {
        return true;
    }

    @Override
    public void tick(World world) {
        this.previousYaw = this.yaw;
        this.previousPitch = this.pitch;
        this.update();
        if (this.hurtTime > 0) this.hurtTime--;
        if (this.damage > 0) this.damage = Math.max(0, this.damage - 1);
        Entity passenger = this.getFirstPassenger();
        if (passenger instanceof EntityPlayer player && player.isDead()) {
            player.stopRiding(world);
        }

        RailPosition rail = this.findRail(world);
        if (rail != null) {
            this.moveOnRail(world, rail);
        } else {
            this.pitch = 0;
            this.motionY -= GRAVITY;
            this.motionX = Math.clamp(this.motionX, -MAX_RAIL_SPEED, MAX_RAIL_SPEED);
            this.motionZ = Math.clamp(this.motionZ, -MAX_RAIL_SPEED, MAX_RAIL_SPEED);
            this.move(world, this.motionX, this.motionY, this.motionZ);
            double drag = this.onGround ? 0.5 : 0.95;
            this.motionX *= drag;
            this.motionY *= 0.95;
            this.motionZ *= drag;
        }

        if (this.motionX * this.motionX + this.motionZ * this.motionZ > 1.0E-6) {
            this.yaw = (float) Math.toDegrees(Math.atan2(this.motionX, -this.motionZ));
        }
        this.positionPassengers();
        world.markChunkModified((int) Math.floor(this.x), (int) Math.floor(this.z));
    }

    private void moveOnRail(World world, RailPosition rail) {
        RailShape shape = RailBehavior.shape(rail.state);
        this.motionY = 0;
        switch (shape) {
            case ASCENDING_EAST -> this.motionX -= SLOPE_ACCELERATION;
            case ASCENDING_WEST -> this.motionX += SLOPE_ACCELERATION;
            case ASCENDING_NORTH -> this.motionZ += SLOPE_ACCELERATION;
            case ASCENDING_SOUTH -> this.motionZ -= SLOPE_ACCELERATION;
            default -> { }
        }

        Segment segment = segment(rail.x, rail.y, rail.z, shape);
        double tx = segment.x1 - segment.x0;
        double ty = segment.y1 - segment.y0;
        double tz = segment.z1 - segment.z0;
        double length = Math.sqrt(tx * tx + tz * tz);
        tx /= length;
        tz /= length;

        double speedSq = this.motionX * this.motionX + this.motionZ * this.motionZ;
        if (speedSq < 0.01 && (this.passengerImpulseX != 0 || this.passengerImpulseZ != 0)) {
            this.motionX += this.passengerImpulseX;
            this.motionZ += this.passengerImpulseZ;
        }
        this.passengerImpulseX = 0;
        this.passengerImpulseZ = 0;

        double speed = Math.sqrt(this.motionX * this.motionX + this.motionZ * this.motionZ);
        double dot = this.motionX * tx + this.motionZ * tz;
        if (dot < 0) { tx = -tx; ty = -ty; tz = -tz; }
        double along = speed;
        along = Math.clamp(along, -MAX_RAIL_SPEED, MAX_RAIL_SPEED);
        this.motionX = tx * along;
        this.motionZ = tz * along;
        this.pitch = (float) Math.toDegrees(Math.atan2(ty, 1.0));

        String id = rail.state.getBlock().getIdentifier().path();
        boolean poweredRail = "powered_rail".equals(id);
        boolean receivesPower = poweredRail && rail.state.get(Properties.POWERED);
        if (poweredRail && !receivesPower) {
            double poweredSpeed = Math.sqrt(this.motionX * this.motionX + this.motionZ * this.motionZ);
            if (poweredSpeed < 0.03) this.motionX = this.motionZ = 0;
            else { this.motionX *= 0.5; this.motionZ *= 0.5; }
        }

        this.motionX = Math.clamp(this.motionX, -MAX_RAIL_SPEED, MAX_RAIL_SPEED);
        this.motionZ = Math.clamp(this.motionZ, -MAX_RAIL_SPEED, MAX_RAIL_SPEED);
        /* Auf Steigungen muss die vertikale Bewegung VOR der horizontalen Blockkollision
           stattfinden. Mit dy=0 prallte die AABB gegen den Stützblock der oberen Schiene. */
        double railYBeforeMove = this.y;
        double movementFactor = this.hasPassengers() ? 0.75 : 1.0;
        this.move(world, this.motionX * movementFactor, ty * along * movementFactor,
                this.motionZ * movementFactor);

        RailPosition after = this.findRailAfterMovement(world);
        if (after != null) {
            this.snapTo(segment(after.x, after.y, after.z, RailBehavior.shape(after.state)));
            /* AbstractMinecart.moveAlongTrack korrigiert nach der zweiten Schienenprojektion
               die horizontale Geschwindigkeit um 0,05 je zurückgelegtem Höhenblock. Diese
               diskrete Schienengravitation kommt zusätzlich zum 0,0078125-Steigungsimpuls. */
            double horizontalSpeed = Math.hypot(this.motionX, this.motionZ);
            if (horizontalSpeed > 0) {
                double correctedSpeed = horizontalSpeed + (railYBeforeMove - this.y) * 0.05;
                this.motionX = this.motionX / horizontalSpeed * correctedSpeed;
                this.motionZ = this.motionZ / horizontalSpeed * correctedSpeed;
            }
        }
        /* Vanilla-Reihenfolge: Erst natürliche Reibung, danach der Impuls einer aktiven
           Antriebsschiene. So wird deren 0,06-Impuls weder mit 0,96 gedämpft noch vorzeitig auf
           die Bewegungshöchstgeschwindigkeit von 0,4 gekappt. */
        this.motionX *= this.hasPassengers() ? 0.997 : 0.96;
        this.motionZ *= this.hasPassengers() ? 0.997 : 0.96;
        if (receivesPower) {
            double poweredSpeed = Math.hypot(this.motionX, this.motionZ);
            if (poweredSpeed > 0.01) {
                this.motionX += this.motionX / poweredSpeed * POWERED_ACCELERATION;
                this.motionZ += this.motionZ / poweredSpeed * POWERED_ACCELERATION;
            } else {
                this.launchFromPoweredRail(world, rail, shape);
            }
        } else if ("activator_rail".equals(id) && rail.state.get(Properties.POWERED)
                && this.getFirstPassenger() != null) {
            this.getFirstPassenger().stopRiding(world);
        }
    }

    private void launchFromPoweredRail(World world, RailPosition rail, RailShape shape) {
        if (RailBehavior.axis(shape) == Direction.Axis.X) {
            boolean westSolid = de.skyengine.game.world.block.Blocks.getState(
                    world.getBlock(rail.x - 1, rail.y, rail.z)).isSolid();
            boolean eastSolid = de.skyengine.game.world.block.Blocks.getState(
                    world.getBlock(rail.x + 1, rail.y, rail.z)).isSolid();
            if (westSolid != eastSolid) this.motionX = westSolid ? 0.02 : -0.02;
        } else if (RailBehavior.axis(shape) == Direction.Axis.Z) {
            boolean northSolid = de.skyengine.game.world.block.Blocks.getState(
                    world.getBlock(rail.x, rail.y, rail.z - 1)).isSolid();
            boolean southSolid = de.skyengine.game.world.block.Blocks.getState(
                    world.getBlock(rail.x, rail.y, rail.z + 1)).isSolid();
            if (northSolid != southSolid) this.motionZ = northSolid ? 0.02 : -0.02;
        }
    }

    @Override
    protected List<AABB> collisionBoxes(World world, AABB area) {
        List<AABB> boxes = super.collisionBoxes(world, area);
        if (boxes.isEmpty() || this.findRail(world) == null) return boxes;
        ArrayList<AABB> filtered = null;
        for (int i = 0; i < boxes.size(); i++) {
            AABB box = boxes.get(i);
            int x = (int) Math.floor((box.minX + box.maxX) * 0.5);
            int yAbove = (int) Math.floor(box.maxY + 1.0E-7);
            int z = (int) Math.floor((box.minZ + box.maxZ) * 0.5);
            if (RailBehavior.railAt(world, x, yAbove, z) == null) {
                if (filtered != null) filtered.add(box);
                continue;
            }
            if (filtered == null) {
                filtered = new ArrayList<>(boxes.size() - 1);
                filtered.addAll(boxes.subList(0, i));
            }
        }
        return filtered != null ? filtered : boxes;
    }

    private void snapTo(Segment segment) {
        double dx = segment.x1 - segment.x0;
        double dz = segment.z1 - segment.z0;
        double lengthSq = dx * dx + dz * dz;
        double t = Math.clamp(((this.x - segment.x0) * dx + (this.z - segment.z0) * dz) / lengthSq,
                0.0, 1.0);
        this.x = segment.x0 + dx * t;
        this.y = segment.y0 + (segment.y1 - segment.y0) * t;
        this.z = segment.z0 + dz * t;
        this.updateBoundingBox();
    }

    private RailPosition findRail(World world) {
        int bx = (int) Math.floor(this.x);
        int bz = (int) Math.floor(this.z);
        int by = (int) Math.floor(this.y);
        BlockState state = RailBehavior.railAt(world, bx, by, bz);
        if (state != null) return new RailPosition(bx, by, bz, state);
        state = RailBehavior.railAt(world, bx, by - 1, bz);
        return state == null ? null : new RailPosition(bx, by - 1, bz, state);
    }

    /**
     * Nach einem bestätigten Schienentick darf das Gefälle die Zielschiene knapp unterschreiten:
     * Die horizontale Reststrecke liegt dann bereits auf der unteren Geraden. Nur dieser Nachlauf
     * prüft deshalb zusätzlich eine Zelle oberhalb; die normale Suche saugt keine freien Carts an.
     */
    private RailPosition findRailAfterMovement(World world) {
        RailPosition rail = this.findRail(world);
        if (rail != null) return rail;
        int bx = (int) Math.floor(this.x);
        int by = (int) Math.floor(this.y) + 1;
        int bz = (int) Math.floor(this.z);
        BlockState state = RailBehavior.railAt(world, bx, by, bz);
        return state == null ? null : new RailPosition(bx, by, bz, state);
    }

    public boolean interact(EntityPlayer player) {
        return player.startRiding(this);
    }

    /**
     * Horizontaler Entity-Kontakt wie bei Vanillas Entity.push: Der Abstand wird normalisiert,
     * bei sehr kleinem Abstand begrenzt und als kleiner Gegenimpuls auf beide Entities verteilt.
     */
    public void pushFrom(Entity other) {
        if (other == null || other == this.getFirstPassenger()) return;
        double dx = other.x - this.x;
        double dz = other.z - this.z;
        double distanceSq = dx * dx + dz * dz;
        if (distanceSq < 1.0E-4) return;
        double distance = Math.sqrt(distanceSq);
        dx = dx / distance * Math.min(1.0, 1.0 / distance) * 0.1 * 0.5;
        dz = dz / distance * Math.min(1.0, 1.0 / distance) * 0.1 * 0.5;
        this.motionX -= dx;
        this.motionZ -= dz;
        /* AbstractMinecart.push gibt der anderen Entity nur ein Viertel des Gegenimpulses. */
        other.motionX += dx / 4.0;
        other.motionZ += dz / 4.0;
    }

    /** Vanilla-Damage-Akkumulator: Zerbruch oberhalb von 40 oder ein erzwungener Werkzeugtreffer. */
    public void attack(World world, boolean creative, boolean efficientTool) {
        this.hurtDirection = -this.hurtDirection;
        this.hurtTime = 10;
        this.damage += 10;
        if (!creative && !efficientTool && this.damage <= 40) return;
        double dropX = this.x, dropY = this.y, dropZ = this.z;
        if (this.getFirstPassenger() != null) this.getFirstPassenger().stopRiding(world);
        this.remove();
        if (!creative) {
            de.skyengine.game.world.item.Item item = de.skyengine.game.world.item.Items.get(
                    de.skyengine.game.world.block.Identifier.of("skyengine:minecart"));
            if (item != null) world.spawnItem(dropX, dropY, dropZ,
                    new de.skyengine.game.world.item.ItemStack(item, 1));
        }
    }

    public float getDamage() { return this.damage; }
    public void setDamage(float damage) { this.damage = Math.max(0, damage); }
    public int getHurtTime() { return this.hurtTime; }
    public void setHurtTime(int hurtTime) { this.hurtTime = Math.max(0, hurtTime); }
    public int getHurtDirection() { return this.hurtDirection; }
    public void setHurtDirection(int direction) { this.hurtDirection = direction < 0 ? -1 : 1; }

    /** Kürzeste Winkelinterpolation zwischen den beiden Simulationsticks. */
    public float renderYaw(float partialTick) {
        return this.previousYaw + wrapDegrees(this.yaw - this.previousYaw) * partialTick;
    }

    public float renderPitch(float partialTick) {
        return this.previousPitch + (this.pitch - this.previousPitch) * partialTick;
    }

    /**
     * Vanillas alter Minecart-Renderer projiziert die interpolierte Entity-Position auf die Schiene
     * und tastet zusätzlich je 0,3 Block vor und hinter dem Cart ab. Der gemittelte Höhenversatz
     * verhindert, dass der Boden an einem Gefälleübergang die Schiene schneidet; dieselben beiden
     * Punkte liefern die geglättete Renderrotation.
     */
    public RenderPose renderPose(World world, float partialTick) {
        double ix = this.lastX + (this.x - this.lastX) * partialTick;
        double iy = this.lastY + (this.y - this.lastY) * partialTick;
        double iz = this.lastZ + (this.z - this.lastZ) * partialTick;
        RailSample center = projectToRail(world, ix, iy, iz);
        if (center == null) {
            return new RenderPose(0, 0, 0, this.renderYaw(partialTick), this.renderPitch(partialTick));
        }

        double horizontalLength = Math.hypot(center.segment.x1 - center.segment.x0,
                center.segment.z1 - center.segment.z0);
        double tx = (center.segment.x1 - center.segment.x0) / horizontalLength;
        double ty = (center.segment.y1 - center.segment.y0) / horizontalLength;
        double tz = (center.segment.z1 - center.segment.z0) / horizontalLength;
        RailSample front = projectToRail(world, ix + tx * 0.3, iy + ty * 0.3, iz + tz * 0.3);
        RailSample back = projectToRail(world, ix - tx * 0.3, iy - ty * 0.3, iz - tz * 0.3);
        if (front == null || back == null) {
            return new RenderPose(center.x - ix, center.y - iy, center.z - iz,
                    this.renderYaw(partialTick), this.renderPitch(partialTick));
        }

        double dx = front.x - back.x;
        double dy = front.y - back.y;
        double dz = front.z - back.z;
        double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (length < 1.0E-7) {
            return new RenderPose(center.x - ix, (front.y + back.y) * 0.5 - iy, center.z - iz,
                    this.renderYaw(partialTick), this.renderPitch(partialTick));
        }
        dx /= length;
        dy /= length;
        dz /= length;

        float fallbackYaw = this.renderYaw(partialTick);
        double forwardX = Math.sin(Math.toRadians(fallbackYaw));
        double forwardZ = -Math.cos(Math.toRadians(fallbackYaw));
        if (dx * forwardX + dz * forwardZ < 0) {
            dx = -dx;
            dy = -dy;
            dz = -dz;
        }
        float sampledYaw = (float) Math.toDegrees(Math.atan2(dx, -dz));
        float sampledPitch = (float) (Math.atan(dy) * 73.0);
        return new RenderPose(center.x - ix, (front.y + back.y) * 0.5 - iy, center.z - iz,
                sampledYaw, sampledPitch);
    }

    private static RailSample projectToRail(World world, double x, double y, double z) {
        int bx = (int) Math.floor(x);
        int by = (int) Math.floor(y);
        int bz = (int) Math.floor(z);
        RailSample closest = null;
        double closestDistance = Double.POSITIVE_INFINITY;
        for (int offsetY = -1; offsetY <= 1; offsetY++) {
            int railY = by + offsetY;
            BlockState state = RailBehavior.railAt(world, bx, railY, bz);
            if (state == null) continue;
            Segment segment = segment(bx, railY, bz, RailBehavior.shape(state));
            double sx = segment.x1 - segment.x0;
            double sz = segment.z1 - segment.z0;
            double t = Math.clamp(((x - segment.x0) * sx + (z - segment.z0) * sz)
                    / (sx * sx + sz * sz), 0.0, 1.0);
            double px = segment.x0 + sx * t;
            double py = segment.y0 + (segment.y1 - segment.y0) * t;
            double pz = segment.z0 + sz * t;
            double distance = (px - x) * (px - x) + (py - y) * (py - y) + (pz - z) * (pz - z);
            if (distance < closestDistance) {
                closestDistance = distance;
                closest = new RailSample(px, py, pz, segment);
            }
        }
        return closest;
    }

    /** Stellt nach dem Laden beide Render-Snapshots ohne einmaligen Rotationssprung her. */
    public void setRotation(float yaw, float pitch) {
        this.yaw = this.previousYaw = yaw;
        this.pitch = this.previousPitch = pitch;
    }

    private static float wrapDegrees(float angle) {
        angle %= 360F;
        if (angle >= 180F) angle -= 360F;
        if (angle < -180F) angle += 360F;
        return angle;
    }

    public void addPassengerImpulse(double x, double z) {
        this.passengerImpulseX = x;
        this.passengerImpulseZ = z;
    }

    @Override
    protected boolean canAddPassenger(Entity passenger) {
        return !this.hasPassengers();
    }

    @Override
    protected void positionPassenger(Entity passenger) {
        passenger.setRidingPosition(this.x, this.y - 0.35, this.z);
    }

    @Override
    protected void positionDismountedPassenger(Entity passenger, World world) {
        double[][] candidates = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (double[] candidate : candidates) {
            passenger.setPosition(this.x + candidate[0], this.y, this.z + candidate[1]);
            if (world.getCollisionBoxes(passenger.getBoundingBox()).isEmpty()) return;
        }
        passenger.setPosition(this.x, this.y + 1, this.z);
    }

    public double rayIntersection(double ox, double oy, double oz, double dx, double dy, double dz,
                                  double maxDistance) {
        double near = 0.0, far = maxDistance;
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

    private static Segment segment(int x, int y, int z, RailShape shape) {
        double h = y + 1 / 16.0;
        return switch (shape) {
            case NORTH_SOUTH -> new Segment(x + 0.5, h, z, x + 0.5, h, z + 1);
            case EAST_WEST -> new Segment(x, h, z + 0.5, x + 1, h, z + 0.5);
            case ASCENDING_EAST -> new Segment(x, h, z + 0.5, x + 1, h + 1, z + 0.5);
            case ASCENDING_WEST -> new Segment(x, h + 1, z + 0.5, x + 1, h, z + 0.5);
            case ASCENDING_NORTH -> new Segment(x + 0.5, h + 1, z, x + 0.5, h, z + 1);
            case ASCENDING_SOUTH -> new Segment(x + 0.5, h, z, x + 0.5, h + 1, z + 1);
            case SOUTH_EAST -> new Segment(x + 0.5, h, z + 1, x + 1, h, z + 0.5);
            case SOUTH_WEST -> new Segment(x, h, z + 0.5, x + 0.5, h, z + 1);
            case NORTH_WEST -> new Segment(x, h, z + 0.5, x + 0.5, h, z);
            case NORTH_EAST -> new Segment(x + 0.5, h, z, x + 1, h, z + 0.5);
        };
    }

    private record RailPosition(int x, int y, int z, BlockState state) {}
    private record RailSample(double x, double y, double z, Segment segment) {}
    private record Segment(double x0, double y0, double z0, double x1, double y1, double z1) {}
    public record RenderPose(double offsetX, double offsetY, double offsetZ, float yaw, float pitch) {}
}
