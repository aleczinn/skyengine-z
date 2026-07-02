package de.skyengine.game.entity;

import de.skyengine.game.physics.AABB;
import de.skyengine.game.world.World;
import de.skyengine.game.world.block.Blocks;
import de.skyengine.game.world.block.behavior.FluidBehavior;
import de.skyengine.game.world.block.state.BlockState;
import de.skyengine.game.world.chunk.FluidGeometry;

import java.util.List;

public abstract class Entity {

    /* --- Fluid-Strömung (Vanilla-Push, pro Tick auf die Motion addiert) --- */
    protected static final double WATER_PUSH = 0.014;
    protected static final double LAVA_PUSH = 0.0023;
    /** Box vor Fluid-Sampling minimal schrumpfen (wie MC), gegen Zellkanten-Berührung. */
    protected static final double FLUID_EPSILON = 0.001;

    /** Position = FUSSPUNKT der Entity (Mitte der Unterseite der BoundingBox) */
    public double x, y, z;
    public double lastX, lastY, lastZ;

    /** Geschwindigkeit in Blöcken pro Tick */
    public double motionX, motionY, motionZ;

    public float yaw, pitch;
    public boolean onGround = false;
    /** true, wenn der letzte {@link #move} horizontal an einem Hindernis geclippt wurde. */
    public boolean horizontalCollision = false;

    /** Maximale Stufenhöhe, die automatisch hochgelaufen wird (0 = aus). */
    public double stepHeight = 0.0;

    protected float width = 0.6F;
    protected float height = 1.8F;

    /** Markiert die Entity zum Entfernen; die Welt räumt sie nach dem Tick aus ihrer Liste. */
    protected boolean removed = false;

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
     * Von der Welt pro Tick für alle gelisteten Entities aufgerufen (nicht der Player - der wird
     * als Sonderfall in {@code GameContainer} getickt). Default: nur der {@code last*}-Snapshot für
     * die Render-Interpolation; bewegte Entities (fallender Block, Item) überschreiben das.
     */
    public void tick(World world) {
        this.update();
    }

    /** Markiert die Entity zum Entfernen (z.B. fallender Block gelandet, Item aufgesammelt). */
    public void remove() {
        this.removed = true;
    }

    public boolean isRemoved() {
        return this.removed;
    }

    /**
     * Ob diese Entity Platz „belegt": verhindert das Setzen eines Blocks an ihrer Stelle (wie in
     * Minecraft der fallende Sand oder ein Mob). Default: nein - Items blockieren nicht.
     */
    public boolean isCollidable() {
        return false;
    }

    /**
     * Bewegt die Entity mit Kollision. Achsenweise: erst Y, dann X, dann Z.
     * Tunneling-sicher bei hoher Geschwindigkeit: die Broadphase-Box deckt
     * über expandTowards() den kompletten Bewegungsweg dieses Ticks ab.
     */
    public void move(World world, double dx, double dy, double dz) {
        /* NoClip: ohne Kollision verschieben, aber denselben Bewegungs-/Positions-Pfad
           wie sonst nutzen (lastX/Y/Z aus update() bleiben erhalten -> Interpolation ok). */
        if (this.isNoClip()) {
            this.boundingBox.move(dx, dy, dz);
            this.x = (this.boundingBox.minX + this.boundingBox.maxX) / 2.0;
            this.y = this.boundingBox.minY;
            this.z = (this.boundingBox.minZ + this.boundingBox.maxZ) / 2.0;
            this.onGround = false;
            this.horizontalCollision = false;
            return;
        }

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
        /* Nach dem Auto-Step: ein vollständig erklommenes Hindernis zählt nicht als Kollision. */
        this.horizontalCollision = nx != origDx || nz != origDz;

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

    /**
     * true, wenn die Box ein Fluid (lava=true: Lava, sonst Wasser) tatsächlich überlappt.
     *
     * <p>Die Box wird vor dem Sampling um {@link #FLUID_EPSILON} geschrumpft (wie Minecraft), damit
     * bloßes Berühren einer Zellkante an deren Minimal-Ecke nicht fälschlich als "im Fluid" zählt.
     * Pro Fluid-Zelle wird zudem gegen die echte Oberkante ({@link FluidGeometry#fluidHeight})
     * geprüft: eine Zelle zählt nur, wenn die Box unter die Fluid-Oberfläche reicht – Stehen knapp
     * über der Oberfläche schwimmt also nicht mehr.
     */
    protected boolean isInFluid(World world, boolean lava) {
        double minX = this.boundingBox.minX + FLUID_EPSILON, maxX = this.boundingBox.maxX - FLUID_EPSILON;
        double minY = this.boundingBox.minY + FLUID_EPSILON, maxY = this.boundingBox.maxY - FLUID_EPSILON;
        double minZ = this.boundingBox.minZ + FLUID_EPSILON, maxZ = this.boundingBox.maxZ - FLUID_EPSILON;

        int x0 = (int) Math.floor(minX), x1 = (int) Math.floor(maxX);
        int y0 = (int) Math.floor(minY), y1 = (int) Math.floor(maxY);
        int z0 = (int) Math.floor(minZ), z1 = (int) Math.floor(maxZ);

        for (int y = y0; y <= y1; y++) {
            for (int x = x0; x <= x1; x++) {
                for (int z = z0; z <= z1; z++) {
                    BlockState state = Blocks.getState(world.getBlock(x, y, z));
                    if (!state.isFluid() || state.getBlock().getFluidInfo().lava != lava) continue;
                    // Fluid füllt [y, y + Höhe]; Box ist im Fluid, wenn sie unter die Oberkante reicht.
                    if (y + FluidGeometry.fluidHeight(state) >= minY) return true;
                }
            }
        }
        return false;
    }

    /**
     * Strömungs-Push (vereinfachtes Vanilla-Fluid-Pushing): mittelt die normierten Flow-Vektoren
     * aller überlappten Fluid-Zellen des Typs und addiert sie mit {@code scale} auf die Motion.
     * Vanillas FALLING-Sog und Mindest-Push für ruhende Entities entfallen.
     */
    protected void applyFluidPush(World world, boolean lava, double scale) {
        double minX = this.boundingBox.minX + FLUID_EPSILON, maxX = this.boundingBox.maxX - FLUID_EPSILON;
        double minY = this.boundingBox.minY + FLUID_EPSILON, maxY = this.boundingBox.maxY - FLUID_EPSILON;
        double minZ = this.boundingBox.minZ + FLUID_EPSILON, maxZ = this.boundingBox.maxZ - FLUID_EPSILON;

        int x0 = (int) Math.floor(minX), x1 = (int) Math.floor(maxX);
        int y0 = (int) Math.floor(minY), y1 = (int) Math.floor(maxY);
        int z0 = (int) Math.floor(minZ), z1 = (int) Math.floor(maxZ);

        double sumX = 0, sumZ = 0;
        double[] flow = new double[2];
        for (int y = y0; y <= y1; y++) {
            for (int x = x0; x <= x1; x++) {
                for (int z = z0; z <= z1; z++) {
                    BlockState state = Blocks.getState(world.getBlock(x, y, z));
                    if (!state.isFluid() || state.getBlock().getFluidInfo().lava != lava) continue;
                    if (y + FluidGeometry.fluidHeight(state) < minY) continue; // unter der Box
                    FluidBehavior.flowVector(world, x, y, z, flow);
                    double len = Math.sqrt(flow[0] * flow[0] + flow[1] * flow[1]);
                    if (len < 1.0E-8) continue;
                    sumX += flow[0] / len;
                    sumZ += flow[1] / len;
                }
            }
        }

        double len = Math.sqrt(sumX * sumX + sumZ * sumZ);
        if (len < 1.0E-8) return;
        this.motionX += sumX / len * scale;
        this.motionZ += sumZ / len * scale;
    }

    public AABB getBoundingBox() {
        return boundingBox;
    }

    /** Wenn true, ignoriert {@link #move} jede Kollision (Standard: aus). */
    public boolean isNoClip() {
        return false;
    }
}