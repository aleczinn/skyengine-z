package de.skyengine.game.entity;

import de.skyengine.game.physics.AABB;
import de.skyengine.game.world.Dimension;
import de.skyengine.game.world.block.Blocks;
import de.skyengine.game.world.block.behavior.FluidBehavior;
import de.skyengine.game.world.block.state.BlockState;
import de.skyengine.game.world.chunk.FluidGeometry;

import java.util.List;
import java.util.ArrayList;

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

    /** Universelle Fahrzeug-/Passenger-Beziehung; Liste wird nur bei echten Fahrzeugen angelegt. */
    private Entity vehicle;
    private ArrayList<Entity> passengers;

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
    public void tick(Dimension world) {
        this.update();
    }

    /** Markiert die Entity zum Entfernen (z.B. fallender Block gelandet, Item aufgesammelt). */
    public void remove() {
        this.removed = true;
        if (this.vehicle != null) {
            Entity oldVehicle = this.vehicle;
            this.vehicle = null;
            oldVehicle.removePassengerInternal(this);
        }
        if (this.passengers != null) {
            for (Entity passenger : this.passengers) passenger.vehicle = null;
            this.passengers.clear();
        }
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

    /** Ob Positions-/Listenänderungen dieser Entity Bestandteil des Chunk-Saves sind. */
    public boolean isPersistent() {
        return false;
    }

    public Entity getVehicle() {
        return this.vehicle;
    }

    public boolean isPassenger() {
        return this.vehicle != null;
    }

    public boolean hasPassengers() {
        return this.passengers != null && !this.passengers.isEmpty();
    }

    protected Entity getFirstPassenger() {
        return this.hasPassengers() ? this.passengers.getFirst() : null;
    }

    /** Beginnt eine universelle Fahrzeugbeziehung; das Fahrzeug entscheidet über seine Kapazität. */
    public boolean startRiding(Entity target) {
        if (target == null || target == this || target.isRemoved()) return false;
        if (this.vehicle == target) return true;
        if (this.vehicle != null || !target.canAddPassenger(this)) return false;
        if (target.passengers == null) target.passengers = new ArrayList<>(1);
        target.passengers.add(this);
        this.vehicle = target;
        target.positionPassenger(this);
        return true;
    }

    /** Standardkapazität null; Fahrzeuge überschreiben diesen Hook. */
    protected boolean canAddPassenger(Entity passenger) {
        return false;
    }

    public void stopRiding(Dimension world) {
        Entity oldVehicle = this.vehicle;
        if (oldVehicle == null) return;
        this.vehicle = null;
        oldVehicle.removePassengerInternal(this);
        oldVehicle.positionDismountedPassenger(this, world);
    }

    private void removePassengerInternal(Entity passenger) {
        if (this.passengers != null) this.passengers.remove(passenger);
    }

    /** Aktualisiert alle Sitzanker; vom Fahrzeug nach seiner eigenen Bewegung aufzurufen. */
    protected void positionPassengers() {
        if (this.passengers == null) return;
        for (int i = 0; i < this.passengers.size(); i++) this.positionPassenger(this.passengers.get(i));
    }

    /** Standard-Sitzanker über dem Fahrzeug; konkrete Fahrzeuge überschreiben ihn. */
    protected void positionPassenger(Entity passenger) {
        passenger.setRidingPosition(this.x, this.y + this.height, this.z);
    }

    /** Standardausstieg über dem Fahrzeug; konkrete Fahrzeuge dürfen sichere Plätze suchen. */
    protected void positionDismountedPassenger(Entity passenger, Dimension world) {
        passenger.setPosition(this.x, this.y + this.height, this.z);
    }

    /** Positions-Sync ohne den Interpolations-Snapshot zu überschreiben. */
    protected void setRidingPosition(double x, double y, double z) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.updateBoundingBox();
    }

    /**
     * Bewegt die Entity mit Kollision. Achsenweise: erst Y, dann X, dann Z.
     * Tunneling-sicher bei hoher Geschwindigkeit: die Broadphase-Box deckt
     * über expandTowards() den kompletten Bewegungsweg dieses Ticks ab.
     */
    public void move(Dimension world, double dx, double dy, double dz) {
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

        /* Position aus der BoundingBox zurücklesen — VOR der Motion-Behandlung, weil der
           Abprall-Test den Block unter den Füßen an der NEUEN Position braucht. */
        this.x = (this.boundingBox.minX + this.boundingBox.maxX) / 2.0;
        this.y = this.boundingBox.minY;
        this.z = (this.boundingBox.minZ + this.boundingBox.maxZ) / 2.0;

        /* Motion auf blockierten Achsen nullen (gegen Wand laufen, auf Boden landen) */
        if (origDx != nx) this.motionX = 0;
        if (origDy != ny) this.motionY = this.landingMotionY(world, origDy);
        if (origDz != nz) this.motionZ = 0;
        if (stepped) this.motionY = 0; // nicht durch den Step nach oben "schießen"

        /* Tempo-Faktor des Blocks (Seelensand/Honig bremsen) — MC wendet ihn am Ende von
           Entity.move an, für jede Entität und ohne nach dem Auslöser der Bewegung zu
           unterscheiden (auch ein Kolben-Schub wird gebremst). Auf normalem Boden ist er 1.0. */
        double speedFactor = this.speedFactor(world);
        if (speedFactor != 1.0) {
            this.motionX *= speedFactor;
            this.motionZ *= speedFactor;
        }

        this.checkInsideBlocks(world);
    }

    /**
     * Meldet jeder Blockzelle, die die finale BoundingBox überlappt, dass eine Entity in ihr
     * steckt ({@code Block.onEntityInside} — Druckplatte). Läuft am Ende jedes move()-Aufrufs,
     * also auch im Stand, weil die Physik jeden Tick bewegt (ggf. um 0) und die Druckplatte die
     * Berührung als Lebenszeichen wertet. Im NoClip (Spectator) bewusst nicht — dort steigt
     * move() vorher aus. Das Epsilon hält exakt bündige Boxen aus der Nachbarzelle heraus.
     */
    private void checkInsideBlocks(Dimension world) {
        final double eps = 1.0E-7;
        int minX = (int) Math.floor(this.boundingBox.minX);
        int minY = (int) Math.floor(this.boundingBox.minY);
        int minZ = (int) Math.floor(this.boundingBox.minZ);
        int maxX = (int) Math.floor(this.boundingBox.maxX - eps);
        int maxY = (int) Math.floor(this.boundingBox.maxY - eps);
        int maxZ = (int) Math.floor(this.boundingBox.maxZ - eps);
        for (int bx = minX; bx <= maxX; bx++) {
            for (int by = minY; by <= maxY; by++) {
                for (int bz = minZ; bz <= maxZ; bz++) {
                    int id = world.getBlock(bx, by, bz);
                    if (id == Blocks.AIR) continue;
                    var state = Blocks.getState(id);
                    state.getBlock().onEntityInside(world, bx, by, bz, state, this);
                }
            }
        }
    }

    /**
     * Neue vertikale Motion nach einem Aufprall. Normalfall 0; auf einem federnden Block
     * (JSON-Feld {@code bounciness}, Slimeblock 1.0) wird die Aufprallgeschwindigkeit stattdessen
     * umgekehrt. Das ist strukturell dieselbe Stelle wie Minecrafts {@code
     * Block.updateEntityAfterFallOn}, dessen Default-Implementierung ebenfalls nur {@code motionY}
     * nullt und die {@code SlimeBlock} durch den Abpraller ersetzt.
     *
     * <p>Der Block wird direkt über die State-Tabelle gelesen und nicht über {@code getBehavior} —
     * das hier ist ein Pro-Tick-Pro-Entity-Pfad, dieselbe Überlegung wie bei der Strömung.
     */
    private double landingMotionY(Dimension world, double origDy) {
        if (origDy >= 0 || this.isSuppressingBounce()) return 0; // Deckenstoß / Sneak: nie federn
        double bounciness = this.blockBelow(world).getBounciness();
        if (bounciness <= 0) return 0;
        return -this.motionY * bounciness * this.bounceDamping();
    }

    /**
     * Dämpfung des Abprallers. Minecraft unterscheidet nur Lebewesen (voll) von allem anderen
     * (0,8) — Drops und gezündetes TNT hüpfen also sichtbar schwächer als der Spieler.
     */
    protected double bounceDamping() {
        return 0.8;
    }

    /** Ob der Abpraller unterdrückt wird (MCs {@code isSuppressingBounce}: Spieler im Sneak). */
    protected boolean isSuppressingBounce() {
        return false;
    }

    /** Block an der eigenen XZ-Spalte auf der Höhe {@code atY} (Luft außerhalb geladener Chunks). */
    protected de.skyengine.game.world.block.Block blockAt(Dimension world, double atY) {
        return Blocks.getState(world.getBlock(
                (int) Math.floor(this.x), (int) Math.floor(atY), (int) Math.floor(this.z))).getBlock();
    }

    /**
     * Der Block, der die Bewegung trägt — MCs {@code getBlockPosBelowThatAffectsMyMovement}
     * ({@code getOnPos(0.500001f)}). Eine halbe Zelle unter der Fußhöhe, damit auch der Block
     * unter einer knapp darüber schwebenden Box zählt.
     */
    protected de.skyengine.game.world.block.Block blockBelow(Dimension world) {
        return this.blockAt(world, this.y - 0.5000001);
    }

    /**
     * Tempo-Faktor des Blocks: erst der an der Fußposition, sonst der darunter (MC
     * {@code Entity.getBlockSpeedFactor} — so bremst Seelensand auch, wenn man knapp darüber
     * steht). Gilt für ALLE Entitäten, deshalb sitzt er hier und nicht am Spieler.
     */
    protected double speedFactor(Dimension world) {
        float own = this.blockAt(world, this.y).getSpeedFactor();
        return own != 1.0F ? own : this.blockBelow(world).getSpeedFactor();
    }

    /**
     * Achsenweise Kollision (Y -> X -> Z) gegen die Broadphase-Boxen. Verschiebt
     * die BoundingBox und liefert die tatsächlich zurückgelegte {dx, dy, dz}.
     */
    private double[] collideAxes(Dimension world, double dx, double dy, double dz) {
        List<AABB> boxes = this.collisionBoxes(world,
                this.boundingBox.copy().expandTowards(dx, dy, dz));

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

    /** Kollisionsboxen für die Bewegung; spezialisierte Entities dürfen gezielt filtern. */
    protected List<AABB> collisionBoxes(Dimension world, AABB area) {
        return world.getCollisionBoxes(area);
    }

    /** true, wenn die Box ein Fluid (lava=true: Lava, sonst Wasser) tatsächlich überlappt. */
    protected boolean isInFluid(Dimension world, boolean lava) {
        return this.fluidDepth(world, lava) > 0;
    }

    /**
     * Eintauchtiefe der Box im Fluid (lava=true: Lava, sonst Wasser): maximales
     * {@code Zell-Oberfläche − box.minY} über alle überlappten Fluid-Zellen, 0 wenn keine
     * (Vanilla {@code getFluidHeight}). Steuert Schwimm-Weiche, Jump-Threshold und Push-Stärke.
     *
     * <p>Die Box wird vor dem Sampling um {@link #FLUID_EPSILON} geschrumpft (wie Minecraft), damit
     * bloßes Berühren einer Zellkante an deren Minimal-Ecke nicht fälschlich als "im Fluid" zählt.
     * Pro Fluid-Zelle wird zudem gegen die echte Oberkante ({@link FluidGeometry#fluidHeight})
     * geprüft: eine Zelle zählt nur, wenn die Box unter die Fluid-Oberfläche reicht – Stehen knapp
     * über der Oberfläche schwimmt also nicht mehr.
     */
    protected double fluidDepth(Dimension world, boolean lava) {
        double minX = this.boundingBox.minX + FLUID_EPSILON, maxX = this.boundingBox.maxX - FLUID_EPSILON;
        double minY = this.boundingBox.minY + FLUID_EPSILON, maxY = this.boundingBox.maxY - FLUID_EPSILON;
        double minZ = this.boundingBox.minZ + FLUID_EPSILON, maxZ = this.boundingBox.maxZ - FLUID_EPSILON;

        int x0 = (int) Math.floor(minX), x1 = (int) Math.floor(maxX);
        int y0 = (int) Math.floor(minY), y1 = (int) Math.floor(maxY);
        int z0 = (int) Math.floor(minZ), z1 = (int) Math.floor(maxZ);

        double depth = 0;
        for (int y = y0; y <= y1; y++) {
            for (int x = x0; x <= x1; x++) {
                for (int z = z0; z <= z1; z++) {
                    BlockState state = Blocks.getState(world.getBlock(x, y, z));
                    if (!state.isFluid() || state.getBlock().getFluidInfo().lava != lava) continue;
                    // Fluid füllt [y, y + Höhe]; Box ist im Fluid, wenn sie unter die Oberkante reicht.
                    depth = Math.max(depth, y + FluidGeometry.fluidHeight(state) - minY);
                }
            }
        }
        return depth;
    }

    /**
     * Strömungs-Push (Vanilla-Fluid-Pushing): mittelt die normierten Flow-Vektoren aller
     * überlappten Fluid-Zellen des Typs und addiert sie mit {@code scale} auf die Motion.
     * Wie in Vanilla wird jeder Zell-Vektor mit der Eintauchtiefe skaliert, solange diese
     * unter 0.4 liegt (flaches Wasser schiebt schwach, quellnah volle Kraft), und nur bei
     * Nicht-Spielern wird das Mittel wieder normiert — der Spieler spürt die Tiefe direkt.
     * Ruhende Entities bekommen einen Mindest-Push (0.0045), damit sie in schwacher Strömung
     * nicht festkleben. Vanillas FALLING-Sog entfällt.
     */
    protected void applyFluidPush(Dimension world, boolean lava, double scale) {
        double minX = this.boundingBox.minX + FLUID_EPSILON, maxX = this.boundingBox.maxX - FLUID_EPSILON;
        double minY = this.boundingBox.minY + FLUID_EPSILON, maxY = this.boundingBox.maxY - FLUID_EPSILON;
        double minZ = this.boundingBox.minZ + FLUID_EPSILON, maxZ = this.boundingBox.maxZ - FLUID_EPSILON;

        int x0 = (int) Math.floor(minX), x1 = (int) Math.floor(maxX);
        int y0 = (int) Math.floor(minY), y1 = (int) Math.floor(maxY);
        int z0 = (int) Math.floor(minZ), z1 = (int) Math.floor(maxZ);

        double sumX = 0, sumZ = 0;
        double depth = 0; // laufendes Maximum der Eintauchtiefe (wie Vanilla)
        int count = 0;
        double[] flow = new double[2];
        for (int y = y0; y <= y1; y++) {
            for (int x = x0; x <= x1; x++) {
                for (int z = z0; z <= z1; z++) {
                    BlockState state = Blocks.getState(world.getBlock(x, y, z));
                    if (!state.isFluid() || state.getBlock().getFluidInfo().lava != lava) continue;
                    double surface = y + FluidGeometry.fluidHeight(state);
                    if (surface < minY) continue; // unter der Box
                    depth = Math.max(depth, surface - minY);
                    count++; // auch Stillwasser-Zellen verdünnen das Mittel (wie Vanilla)
                    FluidBehavior.flowVector(world, x, y, z, flow);
                    double len = Math.sqrt(flow[0] * flow[0] + flow[1] * flow[1]);
                    if (len < 1.0E-8) continue;
                    double cellScale = depth < 0.4 ? depth / len : 1.0 / len;
                    sumX += flow[0] * cellScale;
                    sumZ += flow[1] * cellScale;
                }
            }
        }

        double len = Math.sqrt(sumX * sumX + sumZ * sumZ);
        if (len < 1.0E-8) return;
        sumX /= count;
        sumZ /= count;
        if (!(this instanceof EntityPlayer)) {
            // Nicht-Spieler (Items): Richtung zählt, Stärke ist konstant (Vanilla)
            len = Math.sqrt(sumX * sumX + sumZ * sumZ);
            sumX /= len;
            sumZ /= len;
        }
        double pushX = sumX * scale;
        double pushZ = sumZ * scale;

        /* Mindest-Push (Vanilla): quasi-ruhende Entities bekommen mindestens 0.0045,
           sonst kämen sie in flacher/schwacher Strömung nie in Bewegung. */
        double pushLen = Math.sqrt(pushX * pushX + pushZ * pushZ);
        if (Math.abs(this.motionX) < 0.003 && Math.abs(this.motionZ) < 0.003 && pushLen < 0.0045) {
            pushX = pushX / pushLen * 0.0045;
            pushZ = pushZ / pushLen * 0.0045;
        }
        this.motionX += pushX;
        this.motionZ += pushZ;
    }

    public AABB getBoundingBox() {
        return boundingBox;
    }

    /** Wenn true, ignoriert {@link #move} jede Kollision (Standard: aus). */
    public boolean isNoClip() {
        return false;
    }
}
