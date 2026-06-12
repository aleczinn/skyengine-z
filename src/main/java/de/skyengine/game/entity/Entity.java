package de.skyengine.game.entity;

import de.skyengine.game.physics.AABB;
import de.skyengine.game.world.World;

import java.util.List;

public abstract class Entity {

    /** Position = FUSSPUNKT der Entity (Mitte der Unterseite der BoundingBox) */
    public double x, y, z;
    public double lastX, lastY, lastZ;

    /** Geschwindigkeit in Blöcken pro Tick */
    public double motionX, motionY, motionZ;

    public float yaw, pitch;
    public boolean onGround = false;

    protected float width = 0.6F;
    protected float height = 1.8F;

    protected final AABB boundingBox = new AABB(0, 0, 0, 0, 0, 0);

    protected void setSize(float width, float height) {
        this.width = width;
        this.height = height;
        this.updateBoundingBox();
    }

    public void setPosition(double x, double y, double z) {
        this.x = this.lastX = x;
        this.y = this.lastY = y;
        this.z = this.lastZ = z;
        this.updateBoundingBox();
    }

    protected void updateBoundingBox() {
        double half = this.width / 2.0;
        this.boundingBox.set(
                this.x - half, this.y, this.z - half,
                this.x + half, this.y + this.height, this.z + half
        );
    }

    /**
     * Called once per game tick (20 TPS). Subclasses must call super.tick() FIRST.
     */
    public void update() {
        this.lastX = this.x;
        this.lastY = this.y;
        this.lastZ = this.z;
    }

    /**
     * Bewegt die Entity mit Kollision. Achsenweise: erst Y, dann X, dann Z.
     * Tunneling-sicher bei hoher Geschwindigkeit: die Broadphase-Box deckt
     * über expandTowards() den kompletten Bewegungsweg dieses Ticks ab.
     */
    public void move(World world, double dx, double dy, double dz) {
        double origDx = dx, origDy = dy, origDz = dz;

        List<AABB> boxes = world.getCollisionBoxes(this.boundingBox.copy().expandTowards(dx, dy, dz));

        /* Y zuerst: sorgt dafür, dass man auf Kanten stehen kann statt abzurutschen */
        for (AABB box : boxes) dy = box.clipYCollide(this.boundingBox, dy);
        this.boundingBox.move(0, dy, 0);

        for (AABB box : boxes) dx = box.clipXCollide(this.boundingBox, dx);
        this.boundingBox.move(dx, 0, 0);

        for (AABB box : boxes) dz = box.clipZCollide(this.boundingBox, dz);
        this.boundingBox.move(0, 0, dz);

        this.onGround = origDy != dy && origDy < 0;

        /* Motion auf blockierten Achsen nullen (gegen Wand laufen, auf Boden landen) */
        if (origDx != dx) this.motionX = 0;
        if (origDy != dy) this.motionY = 0;
        if (origDz != dz) this.motionZ = 0;

        /* Position aus der BoundingBox zurücklesen */
        this.x = (this.boundingBox.minX + this.boundingBox.maxX) / 2.0;
        this.y = this.boundingBox.minY;
        this.z = (this.boundingBox.minZ + this.boundingBox.maxZ) / 2.0;
    }

    public AABB getBoundingBox() {
        return boundingBox;
    }
}