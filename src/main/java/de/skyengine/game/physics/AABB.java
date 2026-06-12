package de.skyengine.game.physics;

public class AABB {

    public double minX, minY, minZ;
    public double maxX, maxY, maxZ;

    public AABB(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        this.set(minX, minY, minZ, maxX, maxY, maxZ);
    }

    public AABB set(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        this.minX = minX;
        this.minY = minY;
        this.minZ = minZ;
        this.maxX = maxX;
        this.maxY = maxY;
        this.maxZ = maxZ;
        return this;
    }

    public AABB copy() {
        return new AABB(this.minX, this.minY, this.minZ, this.maxX, this.maxY, this.maxZ);
    }

    public AABB move(double dx, double dy, double dz) {
        this.minX += dx;
        this.minY += dy;
        this.minZ += dz;
        this.maxX += dx;
        this.maxY += dy;
        this.maxZ += dz;
        return this;
    }

    /**
     * Erweitert die Box in Bewegungsrichtung (Broadphase für swept collision).
     * Deckt damit den KOMPLETTEN Bewegungsweg eines Ticks ab - kein Tunneling,
     * egal wie schnell sich die Entity bewegt.
     */
    public AABB expandTowards(double dx, double dy, double dz) {
        if (dx < 0) this.minX += dx; else this.maxX += dx;
        if (dy < 0) this.minY += dy; else this.maxY += dy;
        if (dz < 0) this.minZ += dz; else this.maxZ += dz;
        return this;
    }

    public boolean intersects(AABB other) {
        return other.maxX > this.minX && other.minX < this.maxX
                && other.maxY > this.minY && other.minY < this.maxY
                && other.maxZ > this.minZ && other.minZ < this.maxZ;
    }

    /*
     * clip*Collide: "this" ist das Hindernis (der Block), "other" die bewegte Box.
     * Gibt die maximal mögliche Bewegung auf der Achse zurück, ohne in den Block
     * einzudringen. Funktioniert nur achsenweise (Y -> X -> Z), weil die jeweils
     * anderen Achsen auf Überlappung geprüft werden.
     */

    public double clipXCollide(AABB other, double dx) {
        if (other.maxY <= this.minY || other.minY >= this.maxY) return dx;
        if (other.maxZ <= this.minZ || other.minZ >= this.maxZ) return dx;

        if (dx > 0 && other.maxX <= this.minX) {
            double max = this.minX - other.maxX;
            if (max < dx) dx = max;
        }
        if (dx < 0 && other.minX >= this.maxX) {
            double max = this.maxX - other.minX;
            if (max > dx) dx = max;
        }
        return dx;
    }

    public double clipYCollide(AABB other, double dy) {
        if (other.maxX <= this.minX || other.minX >= this.maxX) return dy;
        if (other.maxZ <= this.minZ || other.minZ >= this.maxZ) return dy;

        if (dy > 0 && other.maxY <= this.minY) {
            double max = this.minY - other.maxY;
            if (max < dy) dy = max;
        }
        if (dy < 0 && other.minY >= this.maxY) {
            double max = this.maxY - other.minY;
            if (max > dy) dy = max;
        }
        return dy;
    }

    public double clipZCollide(AABB other, double dz) {
        if (other.maxX <= this.minX || other.minX >= this.maxX) return dz;
        if (other.maxY <= this.minY || other.minY >= this.maxY) return dz;

        if (dz > 0 && other.maxZ <= this.minZ) {
            double max = this.minZ - other.maxZ;
            if (max < dz) dz = max;
        }
        if (dz < 0 && other.minZ >= this.maxZ) {
            double max = this.maxZ - other.minZ;
            if (max > dz) dz = max;
        }
        return dz;
    }

    @Override
    public String toString() {
        return "AABB[%f, %f, %f -> %f, %f, %f]".formatted(minX, minY, minZ, maxX, maxY, maxZ);
    }
}