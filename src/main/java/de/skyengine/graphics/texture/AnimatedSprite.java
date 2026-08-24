package de.skyengine.graphics.texture;

import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;

/**
 * Ein animiertes Sprite: hält seine Frames (16×16 RGBA) im RAM und liefert den jeweils
 * aktuellen Frame zum Hochladen. Die Animation ist Eigenschaft des Sprites, nicht des
 * Blocks — Lava/Wasser/Feuer/Maschinen nutzen denselben Mechanismus, ohne Re-Mesh.
 */
public final class AnimatedSprite {

    private final int layer;
    private final ByteBuffer[] frames;   // distinkte Frames
    private final int[] sequence;        // Indizes in frames
    private final double frameDuration;  // Sekunden pro Frame
    private final boolean interpolate;
    private final ByteBuffer blended;

    private int cursor;
    private double timer;

    public AnimatedSprite(int layer, ByteBuffer[] frames, int[] sequence, int frametimeTicks) {
        this(layer, frames, sequence, frametimeTicks, false);
    }

    public AnimatedSprite(int layer, ByteBuffer[] frames, int[] sequence, int frametimeTicks,
                          boolean interpolate) {
        this.layer = layer;
        this.frames = frames;
        this.sequence = sequence;
        this.frameDuration = Math.max(1, frametimeTicks) * 0.05;
        this.interpolate = interpolate && sequence.length > 1;
        this.blended = this.interpolate ? MemoryUtil.memAlloc(frames[0].capacity()) : null;
    }

    public int layer() {
        return layer;
    }

    public ByteBuffer initialFrame() {
        return this.frames[this.sequence[0]];
    }

    /** Rückt um dt Sekunden vor; liefert den neuen Frame-Buffer bei Wechsel, sonst null. */
    public ByteBuffer advance(double dt) {
        this.timer += dt;
        boolean changed = false;
        while (this.timer >= this.frameDuration) {
            this.timer -= this.frameDuration;
            this.cursor = (this.cursor + 1) % this.sequence.length;
            changed = true;
        }
        if (!this.interpolate) return changed ? this.frames[this.sequence[this.cursor]] : null;
        ByteBuffer current = this.frames[this.sequence[this.cursor]];
        ByteBuffer next = this.frames[this.sequence[(this.cursor + 1) % this.sequence.length]];
        float progress = (float) Math.clamp(this.timer / this.frameDuration, 0.0, 1.0);
        for (int i = 0; i < this.blended.capacity(); i++) {
            int a = current.get(i) & 0xFF;
            int b = next.get(i) & 0xFF;
            this.blended.put(i, (byte) Math.round(a + (b - a) * progress));
        }
        return this.blended;
    }

    public void dispose() {
        for (ByteBuffer frame : this.frames) MemoryUtil.memFree(frame);
        if (this.blended != null) MemoryUtil.memFree(this.blended);
    }
}
