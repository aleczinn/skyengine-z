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

    private int cursor;
    private double timer;

    public AnimatedSprite(int layer, ByteBuffer[] frames, int[] sequence, int frametimeTicks) {
        this.layer = layer;
        this.frames = frames;
        this.sequence = sequence;
        this.frameDuration = Math.max(1, frametimeTicks) * 0.05;
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
        return changed ? this.frames[this.sequence[this.cursor]] : null;
    }

    public void dispose() {
        for (ByteBuffer frame : this.frames) MemoryUtil.memFree(frame);
    }
}
