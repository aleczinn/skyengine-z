package de.skyengine.game.entity;

public abstract class Entity {

    public double x, y, z;
    public double lastX, lastY, lastZ;

    public float yaw, pitch;

    public void setPosition(double x, double y, double z) {
        this.x = this.lastX = x;
        this.y = this.lastY = y;
        this.z = this.lastZ = z;
    }

    /**
     * Called once per game tick (20 TPS). Subclasses must call super.tick() FIRST.
     */
    public void tick() {
        this.lastX = this.x;
        this.lastY = this.y;
        this.lastZ = this.z;
    }
}
