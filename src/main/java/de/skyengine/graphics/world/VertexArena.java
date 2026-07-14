package de.skyengine.graphics.world;

import de.skyengine.game.world.chunk.ChunkMesher;
import de.skyengine.graphics.GlDebug;
import de.skyengine.utils.logging.LogManager;
import de.skyengine.utils.logging.Logger;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL31;
import org.lwjgl.opengl.GL44;

import java.util.ArrayDeque;
import java.util.Map;
import java.util.TreeMap;

/**
 * Große, device-lokale Vertex-Arena für Section-Meshes eines RenderLayers.
 * Sections mieten {@link Region}en (First-Fit-Free-List) statt eigener VBOs — dadurch kann
 * der ChunkRenderer alle Sections eines Layers mit EINEM MultiDrawIndirect-Call zeichnen
 * (baseVertex = {@link Region#vertexOffset()}).
 *
 * <p>Freigaben sind <b>deferred</b>: Die GPU kann eine Region noch aus den letzten Frames
 * lesen, daher wandern freigegebene Regionen erst nach dem Fence-Signal des Frames zurück
 * in die Free-List ({@link #free} taggt mit der aktuellen Frame-Nummer, {@link #collect}
 * gibt alles bis zur zuletzt abgeschlossenen Frame-Nummer zurück). Nur Render-Thread.
 */
public final class VertexArena {

    /** Bytes pro Vertex (gepacktes Format, siehe {@link ChunkMesher#VERTEX_SIZE}). */
    private static final int VERTEX_BYTES = ChunkMesher.VERTEX_SIZE * Integer.BYTES;

    /* Bewusst NICHT gemappt und NICHT per glBufferSubData beschrieben: beides bewegt den
       Buffer im NVIDIA-Treiber frueher oder spaeter ins Host-RAM ("copied/moved from VIDEO
       memory to HOST memory") und die GPU muesste die Geometrie ueber PCIe ziehen (gemessen:
       bis ~4x FPS-Einbruch). Uploads laufen stattdessen ueber einen kleinen Orphaning-
       Staging-Buffer + glCopyBufferSubData (GPU-seitig) -> die Arena bleibt device-local. */
    private static final int STORAGE_FLAGS = 0;

    /** Ein gemieteter Bereich der Arena. Nach {@link #free} nicht mehr verwenden. */
    public static final class Region {
        private final long offset; // Bytes
        private final long size;   // Bytes

        private Region(long offset, long size) {
            this.offset = offset;
            this.size = size;
        }

        /** Vertex-Index des Region-Anfangs — direkt als baseVertex des Indirect-Commands nutzbar. */
        public int vertexOffset() {
            return (int) (this.offset / VERTEX_BYTES);
        }
    }

    private record PendingFree(Region region, long frame) {}

    private final Logger logger = LogManager.getLogger(VertexArena.class.getName());

    private final String name;
    private int buffer;
    private long capacity;

    /* Free-List: Offset -> Größe (Bytes), zusammenhängend koalesziert */
    private final TreeMap<Long, Long> freeList = new TreeMap<>();
    private final ArrayDeque<PendingFree> pendingFrees = new ArrayDeque<>();

    private long usedBytes = 0;

    /* Staging fuer Uploads: pro alloc() via glBufferData georphant (klassisches Streaming —
       der Treiber verwaltet in-flight Kopien selbst), dann GPU-Copy in die Arena. */
    private final int stagingBuffer;

    public VertexArena(String name, long initialCapacity) {
        this.name = name;
        this.stagingBuffer = GL15.glGenBuffers();
        /* Buffer-Name wird erst durch das erste Bind zum Objekt — sonst GL_INVALID_VALUE beim Label */
        GL15.glBindBuffer(GL31.GL_COPY_READ_BUFFER, this.stagingBuffer);
        GL15.glBindBuffer(GL31.GL_COPY_READ_BUFFER, 0);
        GlDebug.labelBuffer(this.stagingBuffer, name + " Staging");
        this.createBuffer(initialCapacity);
        this.freeList.put(0L, initialCapacity);
    }

    /** GL-Buffer-Name — der Renderer bindet ihn als Vertex-Quelle im VAO (nach Wachstum neu!). */
    public int getBuffer() {
        return this.buffer;
    }

    public long getCapacity() {
        return this.capacity;
    }

    public long getUsedBytes() {
        return this.usedBytes;
    }

    /**
     * Mietet eine Region und kopiert die Mesh-Daten hinein (memcpy in den gemappten Buffer).
     * Wächst bei Bedarf (neuer Buffer -> Renderer muss {@link #getBuffer()} neu binden).
     */
    public Region alloc(int[] meshData) {
        long size = (long) meshData.length * Integer.BYTES;

        Long offset = this.findFirstFit(size);
        if (offset == null) {
            /* Faktor 2 statt 1,5: jeder Grow ist eine GPU-Vollkopie der ganzen Arena (= der
               gemessene Frame-Spike beim Flug) — der größere Faktor halbiert die Anzahl der
               Kopien bis zum Ziel. Die Startgrößen sind ohnehin so gewählt, dass es im
               Normalbetrieb gar nicht erst wächst (ChunkRenderer.init). */
            this.grow(Math.max(this.capacity * 2, this.capacity + size));
            offset = this.findFirstFit(size);
        }

        long blockSize = this.freeList.remove(offset);
        if (blockSize > size) {
            this.freeList.put(offset + size, blockSize - size);
        }
        this.usedBytes += size;

        /* Orphaning-Staging + GPU-Copy: kein CPU-Schreibzugriff auf die Arena selbst */
        GL15.glBindBuffer(GL31.GL_COPY_READ_BUFFER, this.stagingBuffer);
        GL15.glBufferData(GL31.GL_COPY_READ_BUFFER, meshData, GL15.GL_STREAM_DRAW);
        GL15.glBindBuffer(GL31.GL_COPY_WRITE_BUFFER, this.buffer);
        GL31.glCopyBufferSubData(GL31.GL_COPY_READ_BUFFER, GL31.GL_COPY_WRITE_BUFFER, 0, offset, size);
        GL15.glBindBuffer(GL31.GL_COPY_READ_BUFFER, 0);
        GL15.glBindBuffer(GL31.GL_COPY_WRITE_BUFFER, 0);
        return new Region(offset, size);
    }

    /**
     * Gibt eine Region frei — deferred: erst wenn der Frame {@code currentFrame} von der GPU
     * abgeschlossen wurde ({@link #collect}), landet sie wieder in der Free-List.
     */
    public void free(Region region, long currentFrame) {
        if (region == null) return;
        this.pendingFrees.addLast(new PendingFree(region, currentFrame));
    }

    /** Übernimmt alle Deferred-Frees bis einschließlich {@code completedFrame} in die Free-List. */
    public void collect(long completedFrame) {
        while (!this.pendingFrees.isEmpty() && this.pendingFrees.peekFirst().frame <= completedFrame) {
            Region region = this.pendingFrees.removeFirst().region;
            this.usedBytes -= region.size;
            this.insertCoalescing(region.offset, region.size);
        }
    }

    /**
     * Wächst einmalig auf mindestens {@code target} Bytes — statt vieler 1,5x-Schritte mit
     * jeweils voller GPU-Kopie (z.B. beim Einschalten des LOD zur Laufzeit: sonst wächst die
     * Arena vom kleinen Floor treppenweise auf den Steady-State hoch).
     */
    public void ensureCapacity(long target) {
        if (target > this.capacity) this.grow(target);
    }

    private Long findFirstFit(long size) {
        for (Map.Entry<Long, Long> entry : this.freeList.entrySet()) {
            if (entry.getValue() >= size) return entry.getKey();
        }
        return null;
    }

    /** Fügt einen freien Bereich ein und verschmilzt ihn mit direkten Nachbarn. */
    private void insertCoalescing(long offset, long size) {
        Map.Entry<Long, Long> prev = this.freeList.floorEntry(offset);
        if (prev != null && prev.getKey() + prev.getValue() == offset) {
            offset = prev.getKey();
            size += prev.getValue();
            this.freeList.remove(prev.getKey());
        }
        Long nextSize = this.freeList.get(offset + size);
        if (nextSize != null) {
            this.freeList.remove(offset + size);
            size += nextSize;
        }
        this.freeList.put(offset, size);
    }

    /**
     * Vergrößert die Arena: neuer Buffer, GPU-seitige Kopie des alten Inhalts, alter Buffer
     * wird gelöscht (GL hält ihn am Leben, bis ausstehende Commands durch sind). Regionen
     * behalten ihre Offsets — nur die Buffer-Bindung im VAO muss erneuert werden.
     */
    private void grow(long newCapacity) {
        long start = System.nanoTime();
        int oldBuffer = this.buffer;
        long oldCapacity = this.capacity;

        this.createBuffer(newCapacity);

        GL15.glBindBuffer(GL31.GL_COPY_READ_BUFFER, oldBuffer);
        GL15.glBindBuffer(GL31.GL_COPY_WRITE_BUFFER, this.buffer);
        GL31.glCopyBufferSubData(GL31.GL_COPY_READ_BUFFER, GL31.GL_COPY_WRITE_BUFFER, 0, 0, oldCapacity);
        GL15.glBindBuffer(GL31.GL_COPY_READ_BUFFER, 0);
        GL15.glBindBuffer(GL31.GL_COPY_WRITE_BUFFER, 0);
        GL15.glDeleteBuffers(oldBuffer);

        this.insertCoalescing(oldCapacity, newCapacity - oldCapacity);

        /* Grows sind der teuerste Einzel-Frame-Vorgang des Renderers (Vollkopie) und sollten im
           Normalbetrieb NIE auftreten — jede Zeile hier ist ein Hinweis, dass die Startgrößen
           (ChunkRenderer.init) nicht mehr passen. Gemessen wird nur die CPU-Submit-Zeit; die
           eigentliche Kopie läuft asynchron auf der GPU und drückt zusätzlich den Frame. */
        this.logger.debug("Arena-Grow " + this.name + ": " + (oldCapacity >> 20) + " -> "
                + (newCapacity >> 20) + " MB (Submit " + (System.nanoTime() - start) / 1_000_000 + " ms)");
    }

    private void createBuffer(long size) {
        this.buffer = GL15.glGenBuffers();
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, this.buffer);
        GL44.glBufferStorage(GL15.GL_ARRAY_BUFFER, size, STORAGE_FLAGS);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
        this.capacity = size;
        GlDebug.labelBuffer(this.buffer, this.name + " (" + (size >> 20) + " MB)");
    }

    public void dispose() {
        GL15.glDeleteBuffers(this.buffer);
        GL15.glDeleteBuffers(this.stagingBuffer);
        this.freeList.clear();
        this.pendingFrees.clear();
    }
}
