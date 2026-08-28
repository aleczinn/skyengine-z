package de.skyengine.graphics.world;

import de.skyengine.graphics.GlDebug;
import de.skyengine.utils.logging.LogManager;
import de.skyengine.utils.logging.Logger;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL31;
import org.lwjgl.opengl.GL43;
import org.lwjgl.opengl.GL44;

import java.util.ArrayDeque;
import java.util.Map;
import java.util.TreeMap;

/**
 * Device-lokale SSBO-Arena fuer 8- oder 24-Byte-Quads. Offsets bleiben immer auf einem
 * Datensatz ausgerichtet; {@link Region#baseVertex()} bildet einen Quad-Offset auf vier
 * logische Vertices ab. Dadurch koennen die vorhandenen DrawElementsIndirect-Commands und
 * der gemeinsame Quad-EBO unveraendert bleiben, obwohl keine Vertexattribute mehr existieren.
 */
public final class PackedQuadArena {

    public static final int BASE_LONGS = 1;
    public static final int SHADED_LONGS = 3;

    public static final class Region {
        private final long offset, size;
        private final int quadCount, recordBytes;

        private Region(long offset, long size, int quadCount, int recordBytes) {
            this.offset = offset;
            this.size = size;
            this.quadCount = quadCount;
            this.recordBytes = recordBytes;
        }

        public int quadOffset() { return Math.toIntExact(this.offset / this.recordBytes); }
        public int baseVertex() { return Math.multiplyExact(this.quadOffset(), 4); }
        public int quadCount() { return this.quadCount; }
        public int indexCount() { return Math.multiplyExact(this.quadCount, 6); }
    }

    private record PendingFree(Region region, long frame) {}

    private final Logger logger = LogManager.getLogger(PackedQuadArena.class.getName());
    private final String name;
    private final int longsPerQuad, recordBytes;
    private final TreeMap<Long, Long> freeList = new TreeMap<>();
    private final TreeMap<Long, java.util.TreeSet<Long>> freeBySize = new TreeMap<>();
    private final ArrayDeque<PendingFree> pendingFrees = new ArrayDeque<>();
    private final int stagingBuffer;
    private int buffer;
    private long capacity, usedBytes;

    public PackedQuadArena(String name, int longsPerQuad, long initialCapacity) {
        if (longsPerQuad != BASE_LONGS && longsPerQuad != SHADED_LONGS) {
            throw new IllegalArgumentException("Quadformat braucht 1 oder 3 longs");
        }
        this.name = name;
        this.longsPerQuad = longsPerQuad;
        this.recordBytes = longsPerQuad * Long.BYTES;
        initialCapacity = align(Math.max(initialCapacity, this.recordBytes));
        this.stagingBuffer = GL15.glGenBuffers();
        GL15.glBindBuffer(GL31.GL_COPY_READ_BUFFER, this.stagingBuffer);
        GL15.glBindBuffer(GL31.GL_COPY_READ_BUFFER, 0);
        GlDebug.labelBuffer(this.stagingBuffer, name + " Staging");
        this.createBuffer(initialCapacity);
        this.addFree(0, initialCapacity);
    }

    public int getBuffer() { return this.buffer; }
    public long getCapacity() { return this.capacity; }
    public long getUsedBytes() { return this.usedBytes; }
    public int recordBytes() { return this.recordBytes; }

    public Region alloc(long[] data) {
        if (data == null || data.length == 0 || data.length % this.longsPerQuad != 0) {
            throw new IllegalArgumentException("Nichtleere, vollstaendige Quad-Datensaetze erforderlich");
        }
        long size = (long) data.length * Long.BYTES;
        Long offset = this.findBestFit(size);
        if (offset == null) {
            this.grow(align(Math.max(this.capacity * 2, this.capacity + size)));
            offset = this.findBestFit(size);
        }
        long blockSize = this.freeList.get(offset);
        this.removeFree(offset, blockSize);
        if (blockSize > size) this.addFree(offset + size, blockSize - size);
        this.usedBytes += size;

        GL15.glBindBuffer(GL31.GL_COPY_READ_BUFFER, this.stagingBuffer);
        GL15.glBufferData(GL31.GL_COPY_READ_BUFFER, data, GL15.GL_STREAM_DRAW);
        GL15.glBindBuffer(GL31.GL_COPY_WRITE_BUFFER, this.buffer);
        GL31.glCopyBufferSubData(GL31.GL_COPY_READ_BUFFER, GL31.GL_COPY_WRITE_BUFFER,
                0, offset, size);
        GL15.glBindBuffer(GL31.GL_COPY_READ_BUFFER, 0);
        GL15.glBindBuffer(GL31.GL_COPY_WRITE_BUFFER, 0);
        return new Region(offset, size, data.length / this.longsPerQuad, this.recordBytes);
    }

    public void bind(int binding) {
        GL43.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, binding, this.buffer);
    }

    public void free(Region region, long currentFrame) {
        if (region != null) this.pendingFrees.addLast(new PendingFree(region, currentFrame));
    }

    public void collect(long completedFrame) {
        while (!this.pendingFrees.isEmpty() && this.pendingFrees.peekFirst().frame <= completedFrame) {
            Region region = this.pendingFrees.removeFirst().region;
            this.usedBytes -= region.size;
            this.insertCoalescing(region.offset, region.size);
        }
    }

    public void ensureCapacity(long target) {
        target = align(target);
        if (target > this.capacity) this.grow(target);
    }

    private Long findBestFit(long size) {
        Map.Entry<Long, java.util.TreeSet<Long>> entry = this.freeBySize.ceilingEntry(size);
        return entry == null ? null : entry.getValue().first();
    }

    private void insertCoalescing(long offset, long size) {
        Map.Entry<Long, Long> previous = this.freeList.floorEntry(offset);
        if (previous != null && previous.getKey() + previous.getValue() == offset) {
            this.removeFree(previous.getKey(), previous.getValue());
            offset = previous.getKey();
            size += previous.getValue();
        }
        Long next = this.freeList.get(offset + size);
        if (next != null) {
            this.removeFree(offset + size, next);
            size += next;
        }
        this.addFree(offset, size);
    }

    private void addFree(long offset, long size) {
        this.freeList.put(offset, size);
        this.freeBySize.computeIfAbsent(size, ignored -> new java.util.TreeSet<>()).add(offset);
    }

    private void removeFree(long offset, long size) {
        this.freeList.remove(offset);
        java.util.TreeSet<Long> offsets = this.freeBySize.get(size);
        offsets.remove(offset);
        if (offsets.isEmpty()) this.freeBySize.remove(size);
    }

    private void grow(long newCapacity) {
        long started = System.nanoTime();
        int oldBuffer = this.buffer;
        long oldCapacity = this.capacity;
        this.createBuffer(newCapacity);
        GL15.glBindBuffer(GL31.GL_COPY_READ_BUFFER, oldBuffer);
        GL15.glBindBuffer(GL31.GL_COPY_WRITE_BUFFER, this.buffer);
        GL31.glCopyBufferSubData(GL31.GL_COPY_READ_BUFFER, GL31.GL_COPY_WRITE_BUFFER,
                0, 0, oldCapacity);
        GL15.glBindBuffer(GL31.GL_COPY_READ_BUFFER, 0);
        GL15.glBindBuffer(GL31.GL_COPY_WRITE_BUFFER, 0);
        GL15.glDeleteBuffers(oldBuffer);
        this.insertCoalescing(oldCapacity, newCapacity - oldCapacity);
        this.logger.debug("Packed-Arena-Grow " + this.name + ": " + (oldCapacity >> 20)
                + " -> " + (newCapacity >> 20) + " MB (Submit "
                + (System.nanoTime() - started) / 1_000_000 + " ms)");
    }

    private void createBuffer(long size) {
        this.buffer = GL15.glGenBuffers();
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, this.buffer);
        GL44.glBufferStorage(GL43.GL_SHADER_STORAGE_BUFFER, size, 0);
        int error = GL11.glGetError();
        if (error != 0) throw new IllegalStateException("PackedQuadArena " + this.name
                + ": glBufferStorage(" + (size >> 20) + " MB) fehlgeschlagen (GL-Fehler 0x"
                + Integer.toHexString(error) + ")");
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, 0);
        this.capacity = size;
        GlDebug.labelBuffer(this.buffer, this.name + " (" + (size >> 20) + " MB)");
    }

    private long align(long value) {
        long remainder = value % this.recordBytes;
        return remainder == 0 ? value : value + this.recordBytes - remainder;
    }

    public void dispose() {
        GL15.glDeleteBuffers(this.buffer);
        GL15.glDeleteBuffers(this.stagingBuffer);
        this.freeList.clear();
        this.freeBySize.clear();
        this.pendingFrees.clear();
    }
}
