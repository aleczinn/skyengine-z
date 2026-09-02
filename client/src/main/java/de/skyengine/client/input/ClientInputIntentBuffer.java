package de.skyengine.client.input;

/**
 * Renderframe-zu-Clienttick-Puffer. Dauerzustände werden überschrieben, Flanken bleiben bis zur
 * Verarbeitung erhalten und können deshalb bei hohen Bildraten nicht zwischen zwei Ticks verschwinden.
 */
public final class ClientInputIntentBuffer {
    private static final long DOUBLE_TAP_NANOS = 300_000_000L;

    private float forward;
    private float strafe;
    private boolean jumpDown;
    private boolean sneakDown;
    private boolean sprintDown;
    private boolean attackDown;
    private boolean useDown;
    private int attackPresses;
    private int usePresses;
    private int pickPresses;
    private int dropPresses;
    private int flyTogglePresses;
    private int gameModePresses;
    private long lastJumpPressNanos;
    private int pendingHotbarSlot = -1;
    private long pendingHotbarActionId = -1;

    public void captureMovement(float forward, float strafe, boolean jumpDown, boolean sneakDown,
                                boolean sprintDown, boolean attackDown, boolean useDown) {
        this.forward = forward;
        this.strafe = strafe;
        this.jumpDown = jumpDown;
        this.sneakDown = sneakDown;
        this.sprintDown = sprintDown;
        this.attackDown = attackDown;
        this.useDown = useDown;
    }

    public void pressAttack() { this.attackPresses++; }
    public void pressUse() { this.usePresses++; }
    public void pressPick() { this.pickPresses++; }
    public void pressDrop() { this.dropPresses++; }
    public void pressGameMode() { this.gameModePresses++; }

    public void pressJump(long nowNanos) {
        if (this.lastJumpPressNanos != 0 && nowNanos - this.lastJumpPressNanos <= DOUBLE_TAP_NANOS) {
            this.flyTogglePresses++;
            this.lastJumpPressNanos = 0;
        } else {
            this.lastJumpPressNanos = nowNanos;
        }
    }

    public boolean takeAttackPress() { return takeAttack(); }
    public boolean takeUsePress() { return takeUse(); }
    public boolean takePickPress() { return takePick(); }
    public boolean takeDropPress() { return takeDrop(); }
    public boolean takeFlyToggle() { return takeFly(); }
    public boolean takeGameModeCycle() { return takeGameMode(); }

    public float forward() { return this.forward; }
    public float strafe() { return this.strafe; }
    public boolean jumpDown() { return this.jumpDown; }
    public boolean sneakDown() { return this.sneakDown; }
    public boolean sprintDown() { return this.sprintDown; }
    public boolean attackDown() { return this.attackDown; }
    public boolean useDown() { return this.useDown; }

    public void selectHotbarSlot(int slot, long actionId) {
        if (slot < 0 || slot >= 9) throw new IllegalArgumentException("Ungueltiger Hotbar-Slot");
        if (actionId < 0) throw new IllegalArgumentException("Ungueltige Hotbar-Aktions-ID");
        this.pendingHotbarSlot = slot;
        this.pendingHotbarActionId = actionId;
    }

    public int visibleHotbarSlot(int authoritativeSlot) {
        return this.pendingHotbarSlot >= 0 ? this.pendingHotbarSlot : authoritativeSlot;
    }

    public void confirmHotbarSlot(long actionId, int authoritativeSlot) {
        if (actionId < this.pendingHotbarActionId) return;
        if (actionId == this.pendingHotbarActionId) {
            this.pendingHotbarSlot = -1;
            this.pendingHotbarActionId = -1;
        }
    }

    public void clearInteractionPresses() {
        this.attackPresses = 0;
        this.usePresses = 0;
        this.pickPresses = 0;
        this.dropPresses = 0;
    }

    public void reset() {
        this.forward = this.strafe = 0F;
        this.jumpDown = this.sneakDown = this.sprintDown = false;
        this.attackDown = this.useDown = false;
        clearInteractionPresses();
        this.flyTogglePresses = 0;
        this.gameModePresses = 0;
        this.lastJumpPressNanos = 0;
        this.pendingHotbarSlot = -1;
        this.pendingHotbarActionId = -1;
    }

    private boolean takeAttack() { if (this.attackPresses == 0) return false; this.attackPresses--; return true; }
    private boolean takeUse() { if (this.usePresses == 0) return false; this.usePresses--; return true; }
    private boolean takePick() { if (this.pickPresses == 0) return false; this.pickPresses--; return true; }
    private boolean takeDrop() { if (this.dropPresses == 0) return false; this.dropPresses--; return true; }
    private boolean takeFly() { if (this.flyTogglePresses == 0) return false; this.flyTogglePresses--; return true; }
    private boolean takeGameMode() { if (this.gameModePresses == 0) return false; this.gameModePresses--; return true; }
}
