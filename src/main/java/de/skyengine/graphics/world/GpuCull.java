package de.skyengine.graphics.world;

import de.skyengine.graphics.GlDebug;
import de.skyengine.graphics.camera.Camera;
import de.skyengine.graphics.shader.Shader;
import de.skyengine.graphics.shader.ShaderProgram;
import de.skyengine.graphics.shader.ShaderType;
import de.skyengine.utils.logging.LogManager;
import de.skyengine.utils.logging.Logger;
import org.joml.Vector3d;
import org.joml.Vector4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL31;
import org.lwjgl.opengl.GL42;
import org.lwjgl.opengl.GL43;
import org.lwjgl.opengl.GL44;

import java.nio.IntBuffer;
import java.util.ArrayDeque;

/**
 * GPU-Cull-Substrat (P3 der GPU-driven-Roadmap): hält pro zeichenbarer Einheit (Section-Layer
 * bzw. LOD-Region) einen Draw-Descriptor in einem SSBO; ein Compute-Pass testet pro Frame alle
 * Descriptoren gegen das Frustum und kompaktiert Indirect-Commands + Offset-vec4s direkt in
 * GPU-Puffer, gezeichnet wird per {@code glMultiDrawElementsIndirectCount} (Draw-Zahl aus dem
 * Count-Buffer, nie CPU-seitig gelesen). Der CPU-Cull-Pfad im ChunkRenderer bleibt als
 * Fallback/A-B-Vergleich vollständig erhalten — dieses Substrat bringt bei present-gebundener
 * Engine keine FPS, es ist das Fundament für Hi-Z-Occlusion (P4) und den Schatten-Pass.
 *
 * <h2>Warum Snapshot-Ringe statt eines einzelnen Descriptor-Buffers</h2>
 * CPU-Writes in coherent gemappten Speicher sind NICHT gegen die GPU-Timeline geordnet: während
 * die GPU noch den Compute-Pass von Frame N−1 ausführt, schreibt die CPU bereits Mutationen für
 * Frame N — ein einzelner Buffer würde zerrissene Descriptoren (neuer indexCount, alter
 * baseVertex) für einen Frame sichtbar machen. Deshalb werden Descriptoren + Sicht-Gate als
 * CPU-Spiegel geführt und pro Frame-Slot (3, fence-geschützt wie die MDI-Ringe) als konsistenter
 * Snapshot in den gemappten Slot kopiert — nur in Frames, in denen sich etwas geändert hat
 * (Version-Vergleich); im Steady-State kostet das nichts. Slot-Wiederverwendung der Descriptoren
 * ist damit sofort erlaubt (jeder Frame sieht einen in sich konsistenten Stand).
 */
public class GpuCull {

    /**
     * Laufzeit-Schalter (A/B-Vergleich); wirkt nur, wenn die Capabilities vorhanden sind.
     *
     * <p>DEFAULT AUS (Stand 2026-07-17, RTX 4080): Der Pfad ist korrekt und nach der
     * Spike-Diagnose fast paritätisch zum CPU-Cull (solid 55–61 vs. 58 µs, glsub 4–8 µs,
     * keine GPU-Cull-Spikes mehr — gelöst durch (1) plain MDI mit Null-Commands statt
     * glMultiDrawElementsIndirectCount, dessen Count-Lesung die Submission bis zum
     * Pipeline-Leerlauf stallte, und (2) Statistik-Lesung über einen persistent gemappten
     * Read-Ring statt glGetBufferSubData, das den Count-Buffer jeden Frame VIDEO↔HOST
     * verschob). Verbleibende Kosten ~8 % FPS (Compute + Barrier ohne Occlusion-Nutzen) —
     * einschalten, wenn Hi-Z (P4) den Pfad bezahlt; die Descriptor-/Gate-Pflege läuft
     * immer mit, der Toggle greift daher sofort. */
    public static volatile boolean ENABLED = false;

    /* Segmente in Draw-Reihenfolge: Sections OPAQUE, Sections CUTOUT, LOD-Opaque L1..L5.
       Translucent (Sections + LOD) bleibt bewusst CPU (Sortierung bzw. Kleinstmengen). */
    public static final int SEG_OPAQUE = 0;
    public static final int SEG_CUTOUT = 1;
    public static final int SEG_LOD_BASE = 2;           // + (level - 1), Level 1..5
    public static final int MAX_LOD_LEVELS = 5;
    public static final int SEGMENTS = SEG_LOD_BASE + MAX_LOD_LEVELS;

    private static final int SLOTS = 3;                 // muss zu den ChunkRenderer-Fences passen
    private static final int DESC_INTS = 12;            // ivec4 + vec4 + uvec4 (48 Bytes, std430)
    private static final int COMMAND_BYTES = 20;
    private static final int OFFSET_BYTES = 16;
    /* Count-Slot-Stride 256 statt SEGMENTS*4: SSBO-Bind-Offsets müssen das Offset-Alignment
       des Treibers erfüllen (bis 256) — 28-Byte-Schritte wären ab Slot 1 GL_INVALID_VALUE. */
    private static final int COUNT_SLOT_BYTES = MappedRing.ALIGNMENT;

    /* Descriptor-Arrays: 0 = Sections OPAQUE, 1 = Sections CUTOUT, 2 = LOD (alle Level gemischt,
       der Compute filtert per Level-Uniform in die per-Level-Segmente). */
    private static final int DESC_KINDS = 3;
    private static final int DESC_LOD = 2;

    private final Logger logger = LogManager.getLogger(GpuCull.class.getName());

    private boolean supported;
    private ShaderProgram compute;
    private int locPlanes, locCamBlock, locCamFrac, locCount, locLevelFilter,
            locCmdBase, locOffBase, locCountIndex;

    /* CPU-Spiegel je Descriptor-Art + Free-List; version markiert jede Mutation. */
    private final int[][] mirror = new int[DESC_KINDS][];
    private final int[] mirrorCount = new int[DESC_KINDS];      // höchster belegter Slot + 1
    private final ArrayDeque<Integer>[] freeSlots;
    private final int[] lodLevelCount = new int[MAX_LOD_LEVELS + 1];

    /* Sicht-Gate: 1 uint pro Chunk-Spalte (1 = von LOD verdeckt, Sections nicht zeichnen). */
    private int[] gateMirror = new int[4096];
    private int gateCount;
    private final ArrayDeque<Integer> gateFree = new ArrayDeque<>();

    private int version = 1;
    private final int[] appliedVersion = new int[SLOTS];

    /* GL-Objekte: Descriptor-/Gate-Snapshots (persistent gemappt, CPU->GPU), Command-/Offset-/
       Count-Ausgaben (device-local, GPU-geschrieben). Alles über Slot-Offsets geringt. */
    private MappedRing descRing;    // DESC_KINDS Bereiche hintereinander je Slot
    private MappedRing gateRing;
    private int cmdBuffer, offBuffer, countBuffer;
    /* Statistik-Rückweg: GPU kopiert die Counts in diesen persistent-READ-gemappten Ring;
       die CPU liest nach dem Frame-Fence direkt aus dem Mapping — KEIN glGetBufferSubData
       (das ließ den Treiber den Count-Buffer jeden Frame VIDEO<->HOST verschieben und
       stallte bei Upload-Bursts sekundenlang: die 62-99-ms-Spikes). */
    private int countReadBuffer;
    private java.nio.ByteBuffer countReadMapped;
    private long cmdSlotBytes, offSlotBytes;
    private final long[] segCmdBase = new long[SEGMENTS];   // Byte-Basen im Slot (aligned egal, 20B-Raster)
    private final long[] segOffBase = new long[SEGMENTS];
    private final int[] segCapacity = new int[SEGMENTS];

    private int descCapacity;       // Slots je Descriptor-Art (einheitlich, Wachstum verdoppelt)

    private final Vector4f planeScratch = new Vector4f();
    private final float[] planes = new float[24];
    private final int[] countScratch = new int[SEGMENTS];

    @SuppressWarnings("unchecked")
    public GpuCull() {
        this.freeSlots = new ArrayDeque[DESC_KINDS];
        for (int k = 0; k < DESC_KINDS; k++) this.freeSlots[k] = new ArrayDeque<>();
        /* Spiegel immer anlegen: die Descriptor-Pflege läuft auch ohne GPU-Support/bei
           ENABLED=false weiter (billig) — der Laufzeit-Toggle greift dadurch sofort. */
        this.descCapacity = 8192;
        for (int k = 0; k < DESC_KINDS; k++) this.mirror[k] = new int[this.descCapacity * DESC_INTS];
    }

    /** Render-Thread, GL-Kontext nötig. {@code supported=false} lässt alles im CPU-Pfad. */
    public void init(boolean indirectCountSupported) {
        this.supported = indirectCountSupported;
        if (!this.supported) {
            this.logger.info("GPU-Cull: glMultiDrawElementsIndirectCount nicht verfügbar — CPU-Pfad");
            return;
        }

        this.compute = new ShaderProgram(new Shader(COMPUTE_SOURCE, ShaderType.COMPUTE));
        /* Array-Uniforms stehen als "u_Planes[0]" in der Uniform-Map (glGetActiveUniform-Name). */
        this.locPlanes = this.compute.getUniformLocation("u_Planes");
        if (this.locPlanes < 0) this.locPlanes = this.compute.getUniformLocation("u_Planes[0]");
        this.locCamBlock = this.compute.getUniformLocation("u_CamBlock");
        this.locCamFrac = this.compute.getUniformLocation("u_CamFrac");
        this.locCount = this.compute.getUniformLocation("u_Count");
        this.locLevelFilter = this.compute.getUniformLocation("u_LevelFilter");
        this.locCmdBase = this.compute.getUniformLocation("u_CmdBase");
        this.locOffBase = this.compute.getUniformLocation("u_OffBase");
        this.locCountIndex = this.compute.getUniformLocation("u_CountIndex");

        this.descRing = new MappedRing("GpuCull DescRing",
                SLOTS, MappedRing.align((long) DESC_KINDS * this.descCapacity * DESC_INTS * Integer.BYTES));
        this.gateRing = new MappedRing("GpuCull GateRing",
                SLOTS, MappedRing.align((long) this.gateMirror.length * Integer.BYTES));

        this.createOutputBuffers();
        this.logger.info("GPU-Cull: Substrat aktiv (Descriptor-Kapazität " + this.descCapacity + ")");
    }

    /** Legt Command-/Offset-/Count-Puffer passend zur aktuellen Descriptor-Kapazität an. */
    private void createOutputBuffers() {
        long cmd = 0, off = 0;
        for (int s = 0; s < SEGMENTS; s++) {
            this.segCmdBase[s] = cmd;
            this.segOffBase[s] = off;
            this.segCapacity[s] = this.descCapacity;
            cmd += (long) this.descCapacity * COMMAND_BYTES;
            off += (long) this.descCapacity * OFFSET_BYTES;
        }
        this.cmdSlotBytes = MappedRing.align(cmd);
        this.offSlotBytes = MappedRing.align(off);

        this.cmdBuffer = GL15.glGenBuffers();
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, this.cmdBuffer);
        GL44.glBufferStorage(GL43.GL_SHADER_STORAGE_BUFFER, this.cmdSlotBytes * SLOTS, 0);
        GlDebug.labelBuffer(this.cmdBuffer, "GpuCull Commands");

        this.offBuffer = GL15.glGenBuffers();
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, this.offBuffer);
        GL44.glBufferStorage(GL43.GL_SHADER_STORAGE_BUFFER, this.offSlotBytes * SLOTS, 0);
        GlDebug.labelBuffer(this.offBuffer, "GpuCull Offsets");

        this.countBuffer = GL15.glGenBuffers();
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, this.countBuffer);
        GL44.glBufferStorage(GL43.GL_SHADER_STORAGE_BUFFER, (long) SLOTS * COUNT_SLOT_BYTES, 0);
        GlDebug.labelBuffer(this.countBuffer, "GpuCull Counts");

        int readFlags = GL30.GL_MAP_READ_BIT | GL44.GL_MAP_PERSISTENT_BIT | GL44.GL_MAP_COHERENT_BIT;
        this.countReadBuffer = GL15.glGenBuffers();
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, this.countReadBuffer);
        GL44.glBufferStorage(GL43.GL_SHADER_STORAGE_BUFFER, (long) SLOTS * COUNT_SLOT_BYTES, readFlags);
        this.countReadMapped = GL30.glMapBufferRange(GL43.GL_SHADER_STORAGE_BUFFER, 0,
                (long) SLOTS * COUNT_SLOT_BYTES, readFlags);
        GlDebug.labelBuffer(this.countReadBuffer, "GpuCull Counts-Readback");
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, 0);
    }

    public boolean isActive() {
        return this.supported && ENABLED;
    }

    /* ------------------------- Descriptor-/Gate-Pflege (CPU-Spiegel) ------------------------- */

    /** Reserviert einen Gate-Slot für eine Chunk-Spalte (Startwert: sichtbar). */
    public int allocGate(boolean hidden) {
        Integer free = this.gateFree.poll();
        int slot = free != null ? free : this.gateCount++;
        if (slot >= this.gateMirror.length) {
            int[] grown = new int[this.gateMirror.length * 2];
            System.arraycopy(this.gateMirror, 0, grown, 0, this.gateMirror.length);
            this.gateMirror = grown;
            if (this.supported) {
                this.gateRing.ensureSlotCapacity(MappedRing.align((long) this.gateMirror.length * Integer.BYTES));
            }
        }
        this.gateMirror[slot] = hidden ? 1 : 0;
        this.version++;
        return slot;
    }

    public void freeGate(int slot) {
        this.gateMirror[slot] = 0;
        this.gateFree.add(slot);
        this.version++;
    }

    public void setGate(int slot, boolean hidden) {
        int v = hidden ? 1 : 0;
        if (this.gateMirror[slot] == v) return;
        this.gateMirror[slot] = v;
        this.version++;
    }

    /**
     * Registriert einen Section-Layer-Draw. {@code originY} = sectionY·32.
     *
     * @return Descriptor-Slot (für {@link #removeSection})
     */
    public int addSection(int segKind, int blockX, int blockZ, int originY, int gateSlot,
                          int indexCount, int baseVertex) {
        return this.addDesc(segKind, blockX, blockZ, gateSlot, originY,
                originY, originY + 32F, 32F, 1F / 256F, indexCount, baseVertex);
    }

    public void removeSection(int segKind, int slot) {
        this.clearDesc(segKind, slot);
    }

    /** Registriert eine LOD-Region (Level 1..5; Superregionen tragen ihre eigene posScale). */
    public int addLod(int level, int blockX, int blockZ, int yBase, float minY, float maxY,
                      float sizeBlocks, float invPosScale, int indexCount, int baseVertex) {
        this.lodLevelCount[level]++;
        return this.addDesc(DESC_LOD, blockX, blockZ, level, yBase,
                minY, maxY, sizeBlocks, invPosScale, indexCount, baseVertex);
    }

    public void removeLod(int level, int slot) {
        this.lodLevelCount[level]--;
        this.clearDesc(DESC_LOD, slot);
    }

    /** Obergrenze der Draws eines LOD-Levels (für maxDrawCount/Dispatch-Skip). */
    public int lodLevelCount(int level) {
        return this.lodLevelCount[level];
    }

    private int addDesc(int kind, int ix, int iz, int izArg, int iw,
                        float b0, float b1, float b2, float b3, int indexCount, int baseVertex) {
        Integer free = this.freeSlots[kind].poll();
        int slot = free != null ? free : this.mirrorCount[kind]++;
        if (slot >= this.descCapacity) this.growDescriptors();

        int[] m = this.mirror[kind];
        int i = slot * DESC_INTS;
        m[i] = ix;
        m[i + 1] = iz;
        m[i + 2] = izArg;                       // Sections: Gate-Slot, LOD: Level
        m[i + 3] = iw;                          // Welt-Y des Draw-Offsets (originY bzw. yBase)
        m[i + 4] = Float.floatToRawIntBits(b0); // AABB minY (Welt)
        m[i + 5] = Float.floatToRawIntBits(b1); // AABB maxY (Welt)
        m[i + 6] = Float.floatToRawIntBits(b2); // Kantenlänge XZ
        m[i + 7] = Float.floatToRawIntBits(b3); // Positions-Skala (.w des Draw-Offsets)
        m[i + 8] = indexCount;
        m[i + 9] = baseVertex;
        m[i + 10] = 0;
        m[i + 11] = 0;
        this.version++;
        return slot;
    }

    private void clearDesc(int kind, int slot) {
        this.mirror[kind][slot * DESC_INTS + 8] = 0; // indexCount 0 = leerer Slot
        this.freeSlots[kind].add(slot);
        this.version++;
    }

    /** Verdoppelt die Descriptor-Kapazität (selten; Ring-Wachstum synchronisiert per glFinish). */
    private void growDescriptors() {
        int newCapacity = this.descCapacity * 2;
        for (int k = 0; k < DESC_KINDS; k++) {
            int[] grown = new int[newCapacity * DESC_INTS];
            System.arraycopy(this.mirror[k], 0, grown, 0, this.mirror[k].length);
            this.mirror[k] = grown;
        }
        this.descCapacity = newCapacity;
        if (!this.supported) return;

        this.descRing.ensureSlotCapacity(
                MappedRing.align((long) DESC_KINDS * newCapacity * DESC_INTS * Integer.BYTES));
        /* Ausgabepuffer passen nicht mehr (feste Segment-Basen) -> neu anlegen. Wie
           MappedRing.ensureSlotCapacity: glFinish + Neuaufbau (selten, geloggt). */
        GL11.glFinish();
        GL15.glDeleteBuffers(this.cmdBuffer);
        GL15.glDeleteBuffers(this.offBuffer);
        GL15.glDeleteBuffers(this.countBuffer);
        GL15.glDeleteBuffers(this.countReadBuffer); // Delete unmappt implizit
        this.createOutputBuffers();
        this.logger.debug("GPU-Cull: Descriptor-Kapazität gewachsen auf " + newCapacity);
    }

    /* ------------------------- Frame-Pfad ------------------------- */

    /**
     * Kopiert die CPU-Spiegel als konsistenten Snapshot in den Frame-Slot (nur bei Änderung seit
     * dem letzten Besuch dieses Slots) und lässt den Compute-Pass alle Segmente kompaktieren.
     * Aufruf VOR den Draw-Segmenten; der Aufrufer setzt danach die Memory-Barrier.
     */
    public void dispatch(int frameSlot, Camera camera) {
        this.lastDispatchSlot = frameSlot;
        if (this.appliedVersion[frameSlot] != this.version) {
            this.appliedVersion[frameSlot] = this.version;
            IntBuffer desc = this.descRing.intView(frameSlot);
            for (int k = 0; k < DESC_KINDS; k++) {
                desc.position(k * this.descCapacity * DESC_INTS);
                desc.put(this.mirror[k], 0, this.mirrorCount[k] * DESC_INTS);
            }
            desc.position(0);
            IntBuffer gate = this.gateRing.intView(frameSlot);
            gate.position(0);
            gate.put(this.gateMirror, 0, this.gateCount);
            gate.position(0);
        }

        /* Count-Slots dieses Frames nullen (device-local, timeline-geordnet; null-Data = 0). */
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, this.countBuffer);
        GL43.glClearBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER, GL30.GL_R32UI,
                (long) frameSlot * COUNT_SLOT_BYTES, (long) SEGMENTS * Integer.BYTES,
                GL30.GL_RED_INTEGER, GL11.GL_UNSIGNED_INT, (IntBuffer) null);

        /* Frustum-Ebenen aus der kamerarelativen ProjectionView (wie der CPU-Test). */
        for (int p = 0; p < 6; p++) {
            camera.getProjectionViewMatrix().frustumPlane(p, this.planeScratch);
            this.planes[p * 4] = this.planeScratch.x;
            this.planes[p * 4 + 1] = this.planeScratch.y;
            this.planes[p * 4 + 2] = this.planeScratch.z;
            this.planes[p * 4 + 3] = this.planeScratch.w;
        }
        Vector3d cam = camera.getPosition();
        int cbx = (int) Math.floor(cam.x), cby = (int) Math.floor(cam.y), cbz = (int) Math.floor(cam.z);

        this.compute.bind();
        GL20.glUniform4fv(this.locPlanes, this.planes);
        GL20.glUniform3i(this.locCamBlock, cbx, cby, cbz);
        this.compute.setUniformVector3f(this.locCamFrac,
                (float) (cam.x - cbx), (float) (cam.y - cby), (float) (cam.z - cbz));

        GL30.glBindBufferRange(GL43.GL_SHADER_STORAGE_BUFFER, 5, this.cmdBuffer,
                (long) frameSlot * this.cmdSlotBytes, this.cmdSlotBytes);
        GL30.glBindBufferRange(GL43.GL_SHADER_STORAGE_BUFFER, 6, this.offBuffer,
                (long) frameSlot * this.offSlotBytes, this.offSlotBytes);
        GL30.glBindBufferRange(GL43.GL_SHADER_STORAGE_BUFFER, 7, this.countBuffer,
                (long) frameSlot * COUNT_SLOT_BYTES, (long) SEGMENTS * Integer.BYTES);
        GL30.glBindBufferRange(GL43.GL_SHADER_STORAGE_BUFFER, 8, this.gateRing.getBuffer(),
                this.gateRing.slotOffset(frameSlot), MappedRing.align((long) this.gateMirror.length * Integer.BYTES));

        /* Sections: OPAQUE + CUTOUT (Level-Filter -1 = Sicht-Gate aktiv). */
        this.dispatchKind(frameSlot, SEG_OPAQUE, 0, this.mirrorCount[0], -1);
        this.dispatchKind(frameSlot, SEG_CUTOUT, 1, this.mirrorCount[1], -1);
        /* LOD: ein Dispatch pro Level in ein eigenes Segment (per-Level-GPU-Queries + Skip). */
        for (int level = 1; level <= MAX_LOD_LEVELS; level++) {
            if (this.lodLevelCount[level] == 0) continue;
            this.dispatchKind(frameSlot, SEG_LOD_BASE + level - 1, DESC_LOD, this.mirrorCount[DESC_LOD], level);
        }
        this.compute.unbind();
    }

    private void dispatchKind(int frameSlot, int segment, int kind, int count, int levelFilter) {
        if (count == 0) return;
        GL30.glBindBufferRange(GL43.GL_SHADER_STORAGE_BUFFER, 4, this.descRing.getBuffer(),
                this.descRing.slotOffset(frameSlot) + (long) kind * this.descCapacity * DESC_INTS * Integer.BYTES,
                (long) this.descCapacity * DESC_INTS * Integer.BYTES);
        this.compute.setUniformi(this.locCount, count);
        this.compute.setUniformi(this.locLevelFilter, levelFilter);
        /* u_CmdBase in UINT-Einheiten (der Shader indiziert ein uint-Array), u_OffBase in vec4s. */
        this.compute.setUniformi(this.locCmdBase, (int) (this.segCmdBase[segment] / Integer.BYTES));
        this.compute.setUniformi(this.locOffBase, (int) (this.segOffBase[segment] / OFFSET_BYTES));
        this.compute.setUniformi(this.locCountIndex, segment);
        GL43.glDispatchCompute((count + 255) / 256, 1, 1);
    }

    /** Nach allen Dispatches, vor den Draws: Commands/Offsets für Indirect-Draw + Vertex-Shader. */
    public void barrier() {
        GL42.glMemoryBarrier(GL43.GL_COMMAND_BARRIER_BIT | GL43.GL_SHADER_STORAGE_BARRIER_BIT
                | GL42.GL_BUFFER_UPDATE_BARRIER_BIT);
        /* Statistik-Copy (GPU-seitig, pipelined): Counts in den Read-Ring — gelesen wird erst
           nach dem Frame-Fence direkt aus dem Mapping. */
        GL15.glBindBuffer(GL31.GL_COPY_READ_BUFFER, this.countBuffer);
        GL15.glBindBuffer(GL31.GL_COPY_WRITE_BUFFER, this.countReadBuffer);
        GL31.glCopyBufferSubData(GL31.GL_COPY_READ_BUFFER, GL31.GL_COPY_WRITE_BUFFER,
                (long) this.lastDispatchSlot * COUNT_SLOT_BYTES,
                (long) this.lastDispatchSlot * COUNT_SLOT_BYTES, (long) SEGMENTS * Integer.BYTES);
        GL15.glBindBuffer(GL31.GL_COPY_READ_BUFFER, 0);
        GL15.glBindBuffer(GL31.GL_COPY_WRITE_BUFFER, 0);
    }

    private int lastDispatchSlot;

    /* --- Draw-Parameter für den ChunkRenderer --- */

    public int getCommandBuffer() {
        return this.cmdBuffer;
    }

    public int getOffsetBuffer() {
        return this.offBuffer;
    }

    public int getCountBuffer() {
        return this.countBuffer;
    }

    public long commandOffset(int frameSlot, int segment) {
        return frameSlot * this.cmdSlotBytes + this.segCmdBase[segment];
    }

    public long offsetOffset(int frameSlot, int segment) {
        return frameSlot * this.offSlotBytes + this.segOffBase[segment];
    }

    public long offsetBytes(int segment) {
        return (long) this.segCapacity[segment] * OFFSET_BYTES;
    }

    public long countOffset(int frameSlot, int segment) {
        return (long) frameSlot * COUNT_SLOT_BYTES + (long) segment * Integer.BYTES;
    }

    /**
     * Draw-Zahl des Segments für das normale glMultiDrawElementsIndirect: Slot = Descriptor-
     * Index (keine Kompaktierung), also die volle Descriptor-Zahl der jeweiligen Art —
     * gecullte/fremde Slots tragen Null-Commands. Die LOD-Level-Segmente spannen alle
     * LOD-Descriptoren (Löcher sind Null-Commands des jeweils anderen Levels).
     */
    public int drawCount(int segment) {
        if (segment == SEG_OPAQUE) return this.mirrorCount[0];
        if (segment == SEG_CUTOUT) return this.mirrorCount[1];
        return this.mirrorCount[DESC_LOD];
    }

    /**
     * Draw-Zahlen des (fence-bestätigt fertigen) Slots für die Statistik — reine Lesung aus
     * dem persistent gemappten Read-Ring, KEIN GL-Call (s. countReadBuffer-Kommentar).
     */
    public int[] readCounts(int frameSlot) {
        int base = frameSlot * COUNT_SLOT_BYTES;
        for (int i = 0; i < SEGMENTS; i++) {
            this.countScratch[i] = this.countReadMapped.getInt(base + i * Integer.BYTES);
        }
        return this.countScratch;
    }

    public void dispose() {
        if (!this.supported) return;
        this.compute.dispose();
        this.descRing.dispose();
        this.gateRing.dispose();
        GL15.glDeleteBuffers(this.cmdBuffer);
        GL15.glDeleteBuffers(this.offBuffer);
        GL15.glDeleteBuffers(this.countBuffer);
        GL15.glDeleteBuffers(this.countReadBuffer);
        this.countReadMapped = null;
    }

    /* ------------------------- Compute-Shader ------------------------- */

    private static final String COMPUTE_SOURCE = """
            #version 460 core

            layout(local_size_x = 256) in;

            struct DrawDesc {
                ivec4 ipos;    // x=WeltX, y=WeltZ, z=GateSlot|Level, w=Welt-Y des Draw-Offsets
                vec4  bounds;  // x=minY, y=maxY (Welt), z=Kantenlänge XZ, w=Positions-Skala
                uvec4 draw;    // x=indexCount (0=leer), y=baseVertex
            };

            layout(std430, binding = 4) readonly buffer Descriptors { DrawDesc u_Descs[]; };
            layout(std430, binding = 5) writeonly buffer Commands { uint u_Cmds[]; };
            layout(std430, binding = 6) writeonly buffer Offsets { vec4 u_Offs[]; };
            layout(std430, binding = 7) buffer Counts { uint u_Counts[]; };
            layout(std430, binding = 8) readonly buffer Gate { uint u_Hidden[]; };

            uniform vec4 u_Planes[6];
            uniform ivec3 u_CamBlock;
            uniform vec3 u_CamFrac;
            uniform int u_Count;
            uniform int u_LevelFilter; // -1 = Sections (Sicht-Gate aktiv), sonst LOD-Level
            uniform int u_CmdBase;     // in Commands (5-uint-Schritte bereits eingerechnet)
            uniform int u_OffBase;     // in vec4s
            uniform int u_CountIndex;

            void main() {
                uint i = gl_GlobalInvocationID.x;
                if (i >= uint(u_Count)) return;
                uint ci = uint(u_CmdBase) + i * 5u;
                DrawDesc d = u_Descs[i];

                bool sichtbar = d.draw.x != 0u;
                if (sichtbar) {
                    if (u_LevelFilter >= 0) {
                        sichtbar = d.ipos.z == u_LevelFilter;
                    } else {
                        /* Spalte von LOD verdeckt (atomarer Swap wie im CPU-Pfad) */
                        sichtbar = u_Hidden[d.ipos.z] == 0u;
                    }
                }

                /* Kamerarelatives AABB: XZ über exakte int-Differenz (Welt-Koordinaten als int,
                   Kamera-Anker als Block + Bruchteil — float-Präzisions-Falle der Roadmap). */
                float camY = float(u_CamBlock.y) + u_CamFrac.y;
                float ox = float(d.ipos.x - u_CamBlock.x) - u_CamFrac.x;
                float oz = float(d.ipos.y - u_CamBlock.z) - u_CamFrac.z;
                if (sichtbar) {
                    vec3 mn = vec3(ox, d.bounds.x - camY, oz);
                    vec3 mx = vec3(ox + d.bounds.z, d.bounds.y - camY, oz + d.bounds.z);
                    for (int p = 0; p < 6 && sichtbar; p++) {
                        vec4 pl = u_Planes[p];
                        vec3 v = vec3(pl.x >= 0.0 ? mx.x : mn.x,
                                      pl.y >= 0.0 ? mx.y : mn.y,
                                      pl.z >= 0.0 ? mx.z : mn.z);
                        sichtbar = dot(pl.xyz, v) + pl.w >= 0.0;
                    }
                }

                /* Gecullt: Null-Command schreiben (count=0), NICHT einfach return — sonst
                   zeichnen veraltete Slot-Inhalte des Ring-Slots Geister-Geometrie. */
                if (!sichtbar) {
                    u_Cmds[ci] = 0u;
                    return;
                }

                /* Slot = Descriptor-Index (KEINE Kompaktierung): geculltes schreibt oben ein
                   Null-Command. Leere Draws sind auf der GPU fast gratis (gemessen), dafuer
                   entfaellt glMultiDrawElementsIndirectCount samt GL_PARAMETER_BUFFER — dessen
                   Count-Lesung stallte die Submission treiberseitig (76-114-ms-Spikes bei
                   Upload-Bursts) — und die Draw-Reihenfolge bleibt stabil. Der Count-Buffer
                   dient nur noch der Statistik. */
                atomicAdd(u_Counts[u_CountIndex], 1u);
                u_Cmds[ci] = d.draw.x;      // count
                u_Cmds[ci + 1u] = 1u;       // instanceCount
                u_Cmds[ci + 2u] = 0u;       // firstIndex (geteilter EBO ab 0)
                u_Cmds[ci + 3u] = d.draw.y; // baseVertex
                u_Cmds[ci + 4u] = 0u;       // baseInstance
                u_Offs[uint(u_OffBase) + i] = vec4(ox, float(d.ipos.w) - camY, oz, d.bounds.w);
            }
            """;
}
