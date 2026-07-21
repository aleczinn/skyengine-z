package de.skyengine.game.entity;

import de.skyengine.core.input.Input;
import de.skyengine.core.settings.GameSettings;
import de.skyengine.core.settings.KeyBindings;
import de.skyengine.game.Gamemode;
import de.skyengine.game.physics.AABB;
import de.skyengine.game.world.World;
import de.skyengine.utils.math.MathUtils;

public class EntityPlayer extends Entity {

    /* --- Augenhöhe --- */
    private static final float EYE_HEIGHT_STANDING = 1.62F;
    private static final float EYE_HEIGHT_SNEAKING = 1.27F;
    private static final float MOUSE_SENSITIVITY = 0.12F;

    /* --- Minecraft-Physik, alle Werte pro Tick (20 TPS) --- */
    private static final double GRAVITY = 0.08;
    private static final double JUMP_POWER = 0.42;
    private static final double SPRINT_JUMP_BOOST = 0.13;

    private static final double WALK_ACCEL = 0.1;
    private static final double AIR_ACCEL = 0.02;
    private static final double SPRINT_FACTOR = 1.5;
    private static final double STRAFE_FACTOR = 1.2;
    private static final double SNEAK_FACTOR = 0.3;

    private static final double GROUND_FRICTION = 0.546;
    private static final double AIR_DRAG_HORIZONTAL = 0.91;
    private static final double AIR_DRAG_VERTICAL = 0.98;

    private static final double FLY_ACCEL = 0.065;
    private static final double FLY_SPRINT_FACTOR = 20.5;
    private static final double FLY_VERTICAL_FACTOR = 0.6;   // Hoch/Runter langsamer als Vorwärts
    private static final double FLY_DRAG = 0.88;
    private static final double FLY_DRAG_Y = 0.6;

    /* --- Schwimmen (Fluid) --- */
    private static final double SWIM_ACCEL = 0.02;          // langsame Beschleunigung im Fluid
    private static final double SWIM_GRAVITY = 0.02;         // sinkt langsam (statt voller Gravitation)
    private static final double SWIM_UP = 0.04;             // Space = aufschwimmen
    private static final double WATER_DRAG = 0.8;           // horizontale/vertikale Reibung Wasser
    private static final double LAVA_DRAG = 0.5;            // Lava bremst stärker
    private static final double FLUID_JUMP_OUT = 0.3;        // Heraussprung an der Wasserkante (Vanilla)
    private static final double FLUID_JUMP_THRESHOLD = 0.4;  // flacher eingetaucht -> Space = voller Sprung

    /* --- Sneak-Kantenschutz --- */
    private static final double SNEAK_EDGE_STEP = 0.05;      // Schrittweite beim Kürzen der Bewegung
    private static final double SNEAK_EDGE_DROP = 0.6;       // ab dieser Falltiefe gilt "keine Kante mehr"

    /* --- Vitals (nur im SURVIVAL relevant): 1 HP = halbes Herz, 20 = 10 Herzen. --- */
    public static final float MAX_HEALTH = 20;
    public static final int MAX_FOOD = 20;
    /** Fallhöhe in Blöcken, die noch keinen Schaden verursacht (MC: Schaden = Höhe − 3). */
    private static final float FALL_DAMAGE_THRESHOLD = 3;

    private Gamemode gamemode = Gamemode.CREATIVE;
    private boolean flying = false; // Start im Fly-Modus, bis Spawn-Logik existiert
    private boolean sprinting = false;
    private boolean sneaking = false;
    private boolean noClip = false;

    private float health = MAX_HEALTH;
    private int foodLevel = MAX_FOOD;
    private float saturation = 5;
    private float exhaustion = 0;
    private float fallDistance = 0;

    /* Halten/Umschalten-Zustand für Sneak/Sprint + Vor-Tick-Tastenzustand (Flanken-Erkennung) */
    private boolean sneakActive, sprintActive;
    private boolean lastSneakDown, lastSprintDown;

    /* Augenhöhe wird pro Tick Richtung Zielwert interpoliert (weiche Kamera beim Sneaken) */
    private float eyeHeight = EYE_HEIGHT_STANDING;
    private float lastEyeHeight = EYE_HEIGHT_STANDING;

    public EntityPlayer() {
        this.setSize(0.6F, 1.8F);
        this.stepHeight = 0.6; // halbe Slabs/Stufen automatisch hochlaufen (wie Minecraft)
    }

    /**
     * Per-TICK movement (20 TPS). Deterministic - only reads the frozen input state.
     */
    public void update(Input input, World world) {
        super.update();

        /* Tot: keine Steuerung mehr — die Physik läuft weiter (der Körper fällt aus), bis der
           Todesscreen Respawn oder Hauptmenü auslöst. */
        if (this.isDead()) input = Input.EMPTY;

        /* Bewegungs-Keys aus den umbelegbaren KeyBindings (Defaults: WASD/Space/Shift/Strg). */
        GameSettings settings = GameSettings.get();
        double forward = 0, strafe = 0;
        if (input.isKeyDown(settings.key(KeyBindings.FORWARD))) forward += 1;
        if (input.isKeyDown(settings.key(KeyBindings.BACK))) forward -= 1;
        if (input.isKeyDown(settings.key(KeyBindings.RIGHT))) strafe += 1;
        if (input.isKeyDown(settings.key(KeyBindings.LEFT))) strafe -= 1;

        boolean up = input.isKeyDown(settings.key(KeyBindings.JUMP));
        boolean shift = input.isKeyDown(settings.key(KeyBindings.SNEAK));
        boolean sprintKey = input.isKeyDown(settings.key(KeyBindings.SPRINT));

        /* Halten- oder Umschalt-Modus (GameSettings): Toggle flippt auf der Druck-Flanke
           (pro Tick erkannt). Sneak-Flanken im Flug ignorieren — Shift ist dort "Sinken",
           sonst landet man unerwartet schleichend. */
        if (settings.sneakToggle) {
            if (shift && !this.lastSneakDown && !this.flying) this.sneakActive = !this.sneakActive;
        } else {
            this.sneakActive = shift;
        }
        if (settings.sprintToggle) {
            if (sprintKey && !this.lastSprintDown) this.sprintActive = !this.sprintActive;
        } else {
            this.sprintActive = sprintKey;
        }
        this.lastSneakDown = shift;
        this.lastSprintDown = sprintKey;

        /* Sneak nur am Boden-Modus; im Fly-Modus bleibt Shift "runter" */
        this.sneaking = !this.flying && this.sneakActive;

        /* Sprint nur bei Vorwärtsbewegung und nicht beim Sneaken */
        this.sprinting = this.sprintActive && forward > 0 && !this.sneaking;

        /* Augenhöhe weich Richtung Ziel bewegen (~3 Ticks Übergang) */
        this.lastEyeHeight = this.eyeHeight;
        float targetEye = this.sneaking ? EYE_HEIGHT_SNEAKING : EYE_HEIGHT_STANDING;
        this.eyeHeight += (targetEye - this.eyeHeight) * 0.5F;

        if (this.sneaking) {
            forward *= SNEAK_FACTOR;
            strafe *= SNEAK_FACTOR;
        }

        boolean wasOnGround = this.onGround;

        /* Strömung schiebt den Spieler (Vanilla: fliegende Spieler sind ausgenommen). */
        if (!this.flying) {
            this.applyFluidPush(world, false, WATER_PUSH);
            this.applyFluidPush(world, true, LAVA_PUSH);
        }

        if (this.flying) {
            this.travelFlying(world, forward, strafe, up, shift);
        } else {
            double lavaDepth = this.fluidDepth(world, true);
            double waterDepth = this.fluidDepth(world, false);
            if (lavaDepth > 0) {
                this.travelSwimming(world, forward, strafe, up, true, lavaDepth);
            } else if (waterDepth > 0) {
                this.travelSwimming(world, forward, strafe, up, false, waterDepth);
            } else {
                this.travelWalking(world, forward, strafe, up);
            }
        }

        /* Creative-Fly: das Aufkommen auf dem Boden beendet das Fliegen (wie in Minecraft). Nur die
           Flanke (Landung) zählt - so bleibt das Fly-Toggle im Stehen erhalten. Spectator (alwaysFly)
           fliegt weiter. */
        if (this.flying && this.onGround && !wasOnGround && !this.gamemode.isAlwaysFly()) {
            this.flying = false;
        }

        this.updateFallDamage(world, wasOnGround);
    }

    /**
     * Fallhöhe akkumulieren und auf der Landungs-Flanke als Schaden anwenden (MC-Regel:
     * Schaden in HP = Fallhöhe − 3 Blöcke). Fliegen, Fluid-Kontakt oder Aufwärtsbewegung
     * setzen die Fallhöhe zurück (Wasser-Landung ist damit immer schadensfrei).
     */
    private void updateFallDamage(World world, boolean wasOnGround) {
        if (this.flying || this.isTouchingFluid(world)) {
            this.fallDistance = 0;
            return;
        }
        double dy = this.y - this.lastY;
        if (dy < 0) {
            this.fallDistance += (float) -dy;
        } else if (dy > 0) {
            this.fallDistance = 0;
        }
        if (this.onGround) {
            if (!wasOnGround) {
                float damage = this.fallDistance - FALL_DAMAGE_THRESHOLD;
                if (damage > 0) this.damage(damage);
            }
            this.fallDistance = 0;
        }
    }

    /**
     * Schwimmen im Fluid: reduzierte Gravitation + Auftrieb, starke Reibung, Space schwimmt aufwärts.
     * Fluids haben keine Kollision - der Spieler sinkt/schwimmt also durch sie hindurch.
     * {@code depth} = Eintauchtiefe (siehe {@link #fluidDepth}) für die Sprung-Fallunterscheidung.
     */
    private void travelSwimming(World world, double forward, double strafe, boolean up, boolean lava, double depth) {
        this.moveRelative(strafe, forward, SWIM_ACCEL);
        double mx = this.motionX, mz = this.motionZ; // Bewegungsabsicht für den Kanten-Check
        this.move(world, this.motionX, this.motionY, this.motionZ);

        /* Heraussprung an der Kante (Vanilla): horizontal gegen ein Hindernis geschwommen und
           darüber (0.6 höher) ist Platz -> Aufwärts-Boost; wiederholt sich jeden Tick, solange
           die Kollision anhält, bis man auf dem Block steht. max(): ein voller Sprung aus
           flachem Wasser (0.42) darf nicht auf 0.3 gekappt werden, sonst reicht der Bogen
           nicht über die Blockkante. */
        if (this.horizontalCollision && this.isFree(world, mx, 0.6, mz)) {
            this.motionY = Math.max(this.motionY, FLUID_JUMP_OUT);
        }

        double drag = lava ? LAVA_DRAG : WATER_DRAG;
        this.motionX *= drag;
        this.motionZ *= drag;
        this.motionY *= drag;
        this.motionY -= SWIM_GRAVITY;
        if (up) {
            if (this.onGround && depth <= FLUID_JUMP_THRESHOLD) {
                /* Flaches Fluid (Vanilla getFluidJumpThreshold): voller Sprung vom Boden,
                   sonst käme man aus Level-1-Wasser nie über eine Blockkante. */
                this.motionY = JUMP_POWER;
            } else {
                this.motionY += SWIM_UP; // aufschwimmen
            }
        }
    }

    /**
     * true, wenn die um (dx, dy, dz) verschobene Box kollisionsfrei wäre. Präziser
     * {@code intersects}-Test, weil {@link World#getCollisionBoxes} nur eine Broadphase ist
     * (siehe {@link #noGroundUnder}).
     */
    private boolean isFree(World world, double dx, double dy, double dz) {
        AABB probe = this.boundingBox.copy().move(dx, dy, dz);
        for (AABB box : world.getCollisionBoxes(probe)) {
            if (box.intersects(probe)) return false;
        }
        return true;
    }

    /**
     * Normales Movement: Gravitation, Springen, Boden-/Luftreibung, Sneak-Kantenschutz.
     * Reihenfolge wie Minecraft: Beschleunigen -> Bewegen -> Reibung & Gravitation.
     */
    private void travelWalking(World world, double forward, double strafe, boolean jump) {
        if (jump && this.onGround) {
            this.motionY = JUMP_POWER;
            if (this.sprinting) {
                double yawRad = Math.toRadians(this.yaw);
                this.motionX += Math.sin(yawRad) * SPRINT_JUMP_BOOST;
                this.motionZ += -Math.cos(yawRad) * SPRINT_JUMP_BOOST;
            }
        }

        double accel = (this.onGround ? WALK_ACCEL : AIR_ACCEL) * (this.sprinting ? SPRINT_FACTOR : 1.0);
        this.moveRelative(strafe, forward, accel);

        double dx = this.motionX;
        double dy = this.motionY;
        double dz = this.motionZ;

        /* Kantenschutz: beim Sneaken am Boden die Bewegung so kürzen,
           dass die BoundingBox nie komplett über dem Abgrund hängt */
        if (this.sneaking && this.onGround && dy <= 0) {
            double[] adjusted = this.backOffFromEdge(world, dx, dz);
            dx = adjusted[0];
            dz = adjusted[1];
        }

        this.move(world, dx, dy, dz);

        /* Gravitation & Reibung NACH dem Bewegen */
        this.motionY -= GRAVITY;
        this.motionY *= AIR_DRAG_VERTICAL;

        double friction = this.onGround ? GROUND_FRICTION : AIR_DRAG_HORIZONTAL;
        this.motionX *= friction;
        this.motionZ *= friction;
    }

    /**
     * Creative-Fly: keine Gravitation, Space/Shift für hoch/runter,
     * Sprint verdoppelt die Geschwindigkeit.
     */
    private void travelFlying(World world, double forward, double strafe, boolean up, boolean down) {
        double accel = FLY_ACCEL * (this.sprinting ? FLY_SPRINT_FACTOR : 1.0);
        this.moveRelative(strafe, forward, accel);

        /* Horizontale Endgeschwindigkeit (accel / (1 - drag)), vertikal davon ein Anteil. */
        double verticalSpeed = (FLY_ACCEL / (1.0 - FLY_DRAG)) * (this.sprinting ? FLY_SPRINT_FACTOR : 1.0) * FLY_VERTICAL_FACTOR;

        if (up && !down) {
            this.motionY = verticalSpeed;
        } else if (down && !up) {
            this.motionY = -verticalSpeed;
        }

        this.move(world, this.motionX, this.motionY, this.motionZ);

        this.motionX *= FLY_DRAG;
        this.motionY *= FLY_DRAG_Y;
        this.motionZ *= FLY_DRAG;
    }

    /**
     * Wandelt Strafe/Forward-Input in eine Beschleunigung relativ zum Yaw um.
     * Diagonale wird normalisiert, danach wird der STRAFE_FACTOR angewendet.
     */
    private void moveRelative(double strafe, double forward, double accel) {
        double lenSq = strafe * strafe + forward * forward;
        if (lenSq < 1.0E-8) return;

        double len = Math.sqrt(lenSq);
        if (len < 1.0) len = 1.0;

        strafe = strafe / len * accel * STRAFE_FACTOR;
        forward = forward / len * accel;

        double yawRad = Math.toRadians(this.yaw);
        double sin = Math.sin(yawRad);
        double cos = Math.cos(yawRad);

        this.motionX += sin * forward + cos * strafe;
        this.motionZ += -cos * forward + sin * strafe;
    }

    /**
     * Minecraft-Kantenschutz: kürzt dx/dz in 0.05er-Schritten Richtung 0,
     * solange unterhalb der Zielposition (um SNEAK_EDGE_DROP versetzt)
     * KEIN Boden mehr wäre. Erst je Achse einzeln, dann beide kombiniert
     * (für diagonales Sneaken an Ecken).
     */
    private double[] backOffFromEdge(World world, double dx, double dz) {
        double x = dx, z = dz;

        while (x != 0 && this.noGroundUnder(world, x, 0)) {
            x = shrinkTowardsZero(x);
        }
        while (z != 0 && this.noGroundUnder(world, 0, z)) {
            z = shrinkTowardsZero(z);
        }
        while (x != 0 && z != 0 && this.noGroundUnder(world, x, z)) {
            x = shrinkTowardsZero(x);
            z = shrinkTowardsZero(z);
        }

        return new double[]{x, z};
    }

    /**
     * true, wenn unter der um (dx, dz) versetzten und um SNEAK_EDGE_DROP abgesenkten
     * Box KEINE Kollisionsbox tatsächlich liegt.
     *
     * <p>Wichtig: {@link World#getCollisionBoxes} ist nur eine Broadphase und meldet
     * auch Boxen benachbarter Voxel. Bei schmalen Blöcken (Zaun-Pfosten, Glasscheibe,
     * Eisenstäbe), die schmaler als ihr Voxel sind, würde ein reiner {@code isEmpty()}-
     * Test fälschlich "Boden vorhanden" liefern, sobald man seitlich vom Pfosten steht –
     * man liefe beim Sneaken herunter. Darum hier ein präziser Schnitt-Test.
     */
    private boolean noGroundUnder(World world, double dx, double dz) {
        AABB probe = this.boundingBox.copy().move(dx, -SNEAK_EDGE_DROP, dz);
        for (AABB box : world.getCollisionBoxes(probe)) {
            if (box.intersects(probe)) return false;
        }
        return true;
    }

    private static double shrinkTowardsZero(double value) {
        if (value < SNEAK_EDGE_STEP && value > -SNEAK_EDGE_STEP) return 0;
        return value > 0 ? value - SNEAK_EDGE_STEP : value + SNEAK_EDGE_STEP;
    }

    /**
     * Friert die Tick-Interpolation ein (prev = current), z.B. bei offenem GUI. Ohne das würde
     * {@code Camera.follow} weiter zwischen zwei verschiedenen Tick-Positionen interpolieren und die
     * Kamera oszillieren ("jittern"), wenn man die Truhe beim Laufen/Springen öffnet.
     */
    public void snapPrevToCurrent() {
        this.lastX = this.x;
        this.lastY = this.y;
        this.lastZ = this.z;
        this.lastEyeHeight = this.eyeHeight;
    }

    /**
     * Per-FRAME mouse look - applied outside the tick so aiming stays smooth at any FPS.
     */
    public void turn(double deltaX, double deltaY) {
        this.yaw += (float) (deltaX * MOUSE_SENSITIVITY);
        this.pitch += (float) (deltaY * MOUSE_SENSITIVITY);
        this.pitch = MathUtils.clamp(this.pitch, -89.9F, 89.9F);

        if (this.yaw > 360) this.yaw -= 360;
        if (this.yaw < 0) this.yaw += 360;
    }

    public void toggleFlying() {
        /* Survival kann nicht fliegen; im Spectator ist der Flug erzwungen (F tut nichts). */
        if (!this.gamemode.canFly() || this.gamemode.isAlwaysFly()) return;
        this.flying = !this.flying;
        if (this.flying) {
            /* Beim Einschalten Fallgeschwindigkeit abfangen, sonst "fällt" man weiter */
            this.motionY = 0;
        } else {
            /* NoClip nur im Flugmodus - beim Landen abschalten, sonst fällt man durch Blöcke */
            this.noClip = false;
        }
    }

    /** NoClip umschalten - nur im Flugmodus aktivierbar. */
    public void toggleNoClip() {
        /* Spectator-NoClip ist erzwungen und darf nicht abgeschaltet werden. */
        if (this.gamemode.isAlwaysFly()) return;
        if (!this.flying) return;
        this.noClip = !this.noClip;
    }

    public Gamemode getGamemode() {
        return gamemode;
    }

    /** Wechselt den Spielmodus und passt Flug/NoClip an dessen Regeln an. */
    public void setGamemode(Gamemode mode) {
        this.gamemode = mode;
        if (mode.isAlwaysFly()) {
            /* Spectator: dauerhaft fliegen + durch Blöcke fallen. */
            this.flying = true;
            this.noClip = true;
            this.motionY = 0;
        } else {
            this.noClip = false;
            /* Survival: kein Flug. Creative behält seinen aktuellen Flug-Zustand. */
            if (!mode.canFly()) {
                this.flying = false;
            }
        }
    }

    public boolean isFlying() {
        return flying;
    }

    /** Flugzustand direkt setzen (Savegame-Restore) — respektiert die Gamemode-Regeln. */
    public void setFlying(boolean flying) {
        if (this.gamemode.isAlwaysFly()) return;         // Spectator: Flug erzwungen
        if (flying && !this.gamemode.canFly()) return;   // Survival: kein Flug
        this.flying = flying;
        if (flying) {
            this.motionY = 0;
        } else {
            this.noClip = false;
        }
    }

    public boolean isSprinting() {
        return sprinting;
    }

    public boolean isSneaking() {
        return sneaking;
    }

    public boolean isNoClip() {
        return noClip;
    }

    /** true, wenn der Spieler in Wasser oder Lava steht (z.B. für die Laufgeräusch-Sperre). */
    public boolean isTouchingFluid(World world) {
        return this.isInFluid(world, false) || this.isInFluid(world, true);
    }

    /* --- Vitals --- */

    /** Fügt Schaden zu — nur im SURVIVAL (Creative/Spectator sind unverwundbar). */
    public void damage(float amount) {
        if (this.gamemode != Gamemode.SURVIVAL || amount <= 0) return;
        this.health = Math.max(0, this.health - amount);
    }

    public void heal(float amount) {
        if (amount <= 0 || this.isDead()) return;
        this.health = Math.min(MAX_HEALTH, this.health + amount);
    }

    public boolean isDead() {
        return this.health <= 0;
    }

    /** Respawn: volle Herzen/Hunger, Erschöpfung und Fallhöhe zurücksetzen. */
    public void resetVitals() {
        this.health = MAX_HEALTH;
        this.foodLevel = MAX_FOOD;
        this.saturation = 5;
        this.exhaustion = 0;
        this.fallDistance = 0;
    }

    public float getHealth() {
        return health;
    }

    /** Savegame-Restore. */
    public void setHealth(float health) {
        this.health = Math.clamp(health, 0, MAX_HEALTH);
    }

    public int getFoodLevel() {
        return foodLevel;
    }

    /** Savegame-Restore. */
    public void setFoodLevel(int foodLevel) {
        this.foodLevel = Math.clamp(foodLevel, 0, MAX_FOOD);
    }

    public float getSaturation() {
        return saturation;
    }

    /** Savegame-Restore. */
    public void setSaturation(float saturation) {
        this.saturation = Math.clamp(saturation, 0, MAX_FOOD);
    }

    /**
     * Interpolierte Augenhöhe für die Kamera (weicher Sneak-Übergang).
     */
    public float getEyeHeight(float partialTick) {
        return this.lastEyeHeight + (this.eyeHeight - this.lastEyeHeight) * partialTick;
    }
}