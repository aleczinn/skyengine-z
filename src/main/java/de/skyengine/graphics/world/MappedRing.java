package de.skyengine.graphics.world;

import de.skyengine.graphics.GlDebug;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL44;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;

/**
 * Persistent gemappter Ring-Buffer mit Frame-Slots (für Indirect-Commands und das
 * Offset-SSBO des MultiDrawIndirect-Pfads). Der Aufrufer synchronisiert die Slots über
 * Frame-Fences (siehe ChunkRenderer). Slot-Größe ist 256-Byte-aligned, damit
 * SSBO-Bind-Offsets jede GL_SHADER_STORAGE_BUFFER_OFFSET_ALIGNMENT erfüllen. Nur Render-Thread.
 */
final class MappedRing {

    /** Alignment für Slot- und Segment-Starts (deckt SSBO-Offset-Alignment aller Treiber ab). */
    static final int ALIGNMENT = 256;

    private static final int MAP_FLAGS = GL30.GL_MAP_WRITE_BIT | GL44.GL_MAP_PERSISTENT_BIT | GL44.GL_MAP_COHERENT_BIT;

    private final String name;
    private final int slots;
    private long slotSize;
    private int buffer;
    private ByteBuffer mapped;

    MappedRing(String name, int slots, long initialSlotSize) {
        this.name = name;
        this.slots = slots;
        this.create(align(initialSlotSize));
    }

    static long align(long bytes) {
        return (bytes + ALIGNMENT - 1) / ALIGNMENT * ALIGNMENT;
    }

    int getBuffer() {
        return this.buffer;
    }

    long slotOffset(int slot) {
        return slot * this.slotSize;
    }

    /**
     * Stellt sicher, dass ein Slot mindestens {@code bytes} fasst. Wachstum ist selten
     * (sichtbare Draw-Zahl sprengt den Slot) und wartet die GPU einmalig komplett ab,
     * weil der alte Buffer sofort ersetzt wird.
     */
    void ensureSlotCapacity(long bytes) {
        if (bytes <= this.slotSize) return;
        GL11.glFinish();
        GL15.glDeleteBuffers(this.buffer);
        this.create(align(Math.max(bytes, this.slotSize * 2)));
    }

    private void create(long newSlotSize) {
        this.slotSize = newSlotSize;
        this.buffer = GL15.glGenBuffers();
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, this.buffer);
        GL44.glBufferStorage(GL15.GL_ARRAY_BUFFER, newSlotSize * this.slots, MAP_FLAGS);
        this.mapped = GL30.glMapBufferRange(GL15.GL_ARRAY_BUFFER, 0, newSlotSize * this.slots, MAP_FLAGS);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
        if (this.mapped == null) {
            throw new IllegalStateException("MappedRing: persistentes Mapping fehlgeschlagen (" + newSlotSize * this.slots + " Bytes)");
        }
        GlDebug.labelBuffer(this.buffer, this.name);
    }

    /** Int-Sicht auf einen Slot (Index 0 = Slot-Anfang). */
    IntBuffer intView(int slot) {
        return MemoryUtil.memIntBuffer(MemoryUtil.memAddress(this.mapped) + this.slotOffset(slot), (int) (this.slotSize / Integer.BYTES));
    }

    /** Float-Sicht auf einen Slot (Index 0 = Slot-Anfang). */
    FloatBuffer floatView(int slot) {
        return MemoryUtil.memFloatBuffer(MemoryUtil.memAddress(this.mapped) + this.slotOffset(slot), (int) (this.slotSize / Float.BYTES));
    }

    void dispose() {
        GL15.glDeleteBuffers(this.buffer);
        this.mapped = null;
    }
}
