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

    /** Maximale Stufenhöhe, die automatisch hochgelaufen wird (0 = aus). */
    public double stepHeight = 0.0;

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
        AABB before = this.boundingBox.copy();

        double[] n = this.collideAxes(world, dx, dy, dz);
        double nx = n[0], ny = n[1], nz = n[2];

        boolean collidedHoriz = nx != origDx || nz != origDz;
        boolean grounded = this.onGround || (origDy != ny && origDy < 0);
        boolean stepped = false;

        /* Auto-Step: gegen ein Hindernis gelaufen und am Boden -> versuchen,
           es hochzusteigen (Slabs, Treppen). Behält die Variante mit mehr
           horizontaler Strecke. */
        if (this.stepHeight > 0 && collidedHoriz && grounded) {
            AABB normalBox = this.boundingBox.copy();
            double normalDist = nx * nx + nz * nz;

            this.boundingBox.set(before.minX, before.minY, before.minZ, before.maxX, before.maxY, before.maxZ);
            double[] up = this.collideAxes(world, origDx, this.stepHeight, origDz);
            this.collideAxes(world, 0, -up[1], 0); // wieder absenken (nur das Erklommene)

            if (up[0] * up[0] + up[2] * up[2] > normalDist) {
                nx = up[0];
                nz = up[2];
                stepped = true;
            } else {
                this.boundingBox.set(normalBox.minX, normalBox.minY, normalBox.minZ,
                        normalBox.maxX, normalBox.maxY, normalBox.maxZ);
            }
        }

        this.onGround = stepped || (origDy != ny && origDy < 0);

        /* Motion auf blockierten Achsen nullen (gegen Wand laufen, auf Boden landen) */
        if (origDx != nx) this.motionX = 0;
        if (origDy != ny) this.motionY = 0;
        if (origDz != nz) this.motionZ = 0;
        if (stepped) this.motionY = 0; // nicht durch den Step nach oben "schießen"

        /* Position aus der BoundingBox zurücklesen */
        this.x = (this.boundingBox.minX + this.boundingBox.maxX) / 2.0;
        this.y = this.boundingBox.minY;
        this.z = (this.boundingBox.minZ + this.boundingBox.maxZ) / 2.0;
    }

    /**
     * Achsenweise Kollision (Y -> X -> Z) gegen die Broadphase-Boxen. Verschiebt
     * die BoundingBox und liefert die tatsächlich zurückgelegte {dx, dy, dz}.
     */
    private double[] collideAxes(World world, double dx, double dy, double dz) {
        List<AABB> boxes = world.getCollisionBoxes(this.boundingBox.copy().expandTowards(dx, dy, dz));

        double cdy = dy;
        for (AABB box : boxes) cdy = box.clipYCollide(this.boundingBox, cdy);
        this.boundingBox.move(0, cdy, 0);

        double cdx = dx;
        for (AABB box : boxes) cdx = box.clipXCollide(this.boundingBox, cdx);
        this.boundingBox.move(cdx, 0, 0);

        double cdz = dz;
        for (AABB box : boxes) cdz = box.clipZCollide(this.boundingBox, cdz);
        this.boundingBox.move(0, 0, cdz);

        return new double[]{cdx, cdy, cdz};
    }

    public AABB getBoundingBox() {
        return boundingBox;
    }
}