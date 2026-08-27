package de.skyengine.graphics.entity;

import de.skyengine.game.entity.Entity;
import de.skyengine.game.entity.EntityPlayer;
import de.skyengine.game.physics.AABB;
import de.skyengine.game.world.Dimension;
import de.skyengine.graphics.camera.Camera;
import de.skyengine.graphics.world.DebugLineRenderer;
import org.joml.Vector3d;

import java.util.Arrays;

/** F3+B-Debugdarstellung fuer Entity-AABBs und ihre Blick-/Fahrtrichtung. */
public final class EntityHitboxRenderer {

    private final DebugLineRenderer lines = new DebugLineRenderer();
    private float[] boxes = new float[24 * 3 * 32];
    private float[] directions = new float[10 * 3 * 32];
    private int boxCount, directionCount;

    public void init() {
        this.lines.init("Entity-Hitbox VBO (Streaming)");
    }

    public void render(Camera camera, EntityPlayer player, Dimension world, float partialTick) {
        this.boxCount = 0;
        this.directionCount = 0;
        this.add(player, camera, partialTick, true);
        world.forEachLoadedEntity(entity -> {
            if (!entity.isRemoved()) this.add(entity, camera, partialTick, false);
        });
        this.lines.render(camera, this.boxes, this.boxCount, 2.0F, 1F, 1F, 1F, 1F);
        this.lines.render(camera, this.directions, this.directionCount, 2.0F, 0.1F, 0.25F, 1F, 1F);
    }

    private void add(Entity entity, Camera camera, float partialTick, boolean player) {
        Vector3d cam = camera.getPosition();
        double ix = entity.lastX + (entity.x - entity.lastX) * partialTick;
        double iy = entity.lastY + (entity.y - entity.lastY) * partialTick;
        double iz = entity.lastZ + (entity.z - entity.lastZ) * partialTick;
        AABB current = entity.getBoundingBox();
        double dx = ix - entity.x - cam.x;
        double dy = iy - entity.y - cam.y;
        double dz = iz - entity.z - cam.z;
        double epsilon = 0.002;
        this.box(current.minX + dx - epsilon, current.minY + dy - epsilon,
                current.minZ + dz - epsilon, current.maxX + dx + epsilon,
                current.maxY + dy + epsilon, current.maxZ + dz + epsilon);

        double eyeY = player
                ? iy + ((EntityPlayer) entity).getEyeHeight(partialTick)
                : iy + (current.maxY - current.minY) * 0.85;
        double yaw = Math.toRadians(entity.yaw);
        double pitch = Math.toRadians(entity.pitch);
        double cosPitch = Math.cos(pitch);
        double vx = Math.sin(yaw) * cosPitch;
        double vy = -Math.sin(pitch);
        double vz = -Math.cos(yaw) * cosPitch;
        this.arrow(ix - cam.x, eyeY - cam.y, iz - cam.z, vx, vy, vz);
    }

    private void box(double x0, double y0, double z0, double x1, double y1, double z1) {
        this.boxLine(x0,y0,z0,x1,y0,z0); this.boxLine(x1,y0,z0,x1,y0,z1);
        this.boxLine(x1,y0,z1,x0,y0,z1); this.boxLine(x0,y0,z1,x0,y0,z0);
        this.boxLine(x0,y1,z0,x1,y1,z0); this.boxLine(x1,y1,z0,x1,y1,z1);
        this.boxLine(x1,y1,z1,x0,y1,z1); this.boxLine(x0,y1,z1,x0,y1,z0);
        this.boxLine(x0,y0,z0,x0,y1,z0); this.boxLine(x1,y0,z0,x1,y1,z0);
        this.boxLine(x1,y0,z1,x1,y1,z1); this.boxLine(x0,y0,z1,x0,y1,z1);
    }

    private void arrow(double x, double y, double z, double vx, double vy, double vz) {
        double length = 2.0;
        double tx = x + vx * length, ty = y + vy * length, tz = z + vz * length;
        this.directionLine(x, y, z, tx, ty, tz);
        double sx = -vz, sz = vx;
        double sideLength = Math.sqrt(sx * sx + sz * sz);
        if (sideLength < 1.0E-6) { sx = 1; sz = 0; }
        else { sx /= sideLength; sz /= sideLength; }
        double back = 0.28, side = 0.14;
        this.directionLine(tx, ty, tz,
                tx - vx * back + sx * side, ty - vy * back, tz - vz * back + sz * side);
        this.directionLine(tx, ty, tz,
                tx - vx * back - sx * side, ty - vy * back, tz - vz * back - sz * side);
    }

    private void boxLine(double x0,double y0,double z0,double x1,double y1,double z1) {
        if (this.boxCount + 6 > this.boxes.length) this.boxes = Arrays.copyOf(this.boxes, this.boxes.length * 2);
        this.boxCount = line(this.boxes, this.boxCount, x0,y0,z0,x1,y1,z1);
    }

    private void directionLine(double x0,double y0,double z0,double x1,double y1,double z1) {
        if (this.directionCount + 6 > this.directions.length) {
            this.directions = Arrays.copyOf(this.directions, this.directions.length * 2);
        }
        this.directionCount = line(this.directions, this.directionCount, x0,y0,z0,x1,y1,z1);
    }

    private static int line(float[] data, int at,
                            double x0,double y0,double z0,double x1,double y1,double z1) {
        data[at++] = (float) x0; data[at++] = (float) y0; data[at++] = (float) z0;
        data[at++] = (float) x1; data[at++] = (float) y1; data[at++] = (float) z1;
        return at;
    }

    public void dispose() {
        this.lines.dispose();
    }
}
