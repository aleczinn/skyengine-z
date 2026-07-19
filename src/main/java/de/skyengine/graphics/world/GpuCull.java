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
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
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
     * <p>DEFAULT AN (Stand 2026-07-19, RTX 4080). Frustum-Teil ist nach der Spike-Diagnose
     * paritätisch zum CPU-Cull (plain MDI mit Null-Commands + Statistik über den persistent
     * gemappten Read-Ring); die Hi-Z-Occlusion läuft als TWO-PHASE (Roadmap P4): Phase 1
     * zeichnet die Letzte-Frame-Sichtbaren (Vis-Bit pro Descriptor-Slot), daraus wird die
     * Depth-Pyramide DESSELBEN Frames gebaut, Phase 2 testet alle Descriptoren dagegen
     * (aktuelle Matrix/Kamera, keine Latenz) und zeichnet Nachzügler im selben Frame.
     * Kern-Invariante: das Vis-Bit entscheidet nur die PHASEN-ZUORDNUNG, nie die
     * Sichtbarkeit — stale Bits (K-Toggle, Kaltstart, Buffer-Neuaufbau) kosten höchstens
     * einen Frame Phase-2-Umweg, nie ein Loch.
     *
     * <p>Historie: die Ein-Phasen-Vorstufe hatte einen per Telemetrie BEWIESENEN
     * Selbst-Feedback-Loop — gecullte Objekte fehlten in der Pyramide des Folgeframes und
     * wurden durch ihre eigene Abwesenheit wieder sichtbar (LOD-Ring-Flackern in Linien;
     * Draw-Counts oszillierten op 270..336 / L4 24..83 bei statischer Kamera, mit Debug-Rot
     * perfekt stabil). Die 4-Frame-Streak-Hysterese verschob nur die Periode — deshalb
     * Two-Phase statt Hysterese. Werkzeuge: Hotkey K = GPU-Pfad an/aus, J = Debug-Rot,
     * 1-s-Telemetrie „GpuCull-Draws"/„GpuCull-Occlusion" (DebugMode.FULL). */
    public static volatile boolean ENABLED = true;

    /** Occlusion-Debug (Hotkey J): Verdeckt-Verdikte werden ROT gezeichnet statt gecullt. */
    public static volatile boolean DEBUG_TINT = false;

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
            locCmdBase, locOffBase, locCountIndex, locPhase;

    /* CPU-Spiegel je Descriptor-Art + Free-List; version markiert jede Mutation. */
    private final int[][] mirror = new int[DESC_KINDS][];
    private final int[] mirrorCount = new int[DESC_KINDS];      // höchster belegter Slot + 1
    private final ArrayDeque<Integer>[] freeSlots;

    /* Pro Slot rotierende 8-Bit-Generation (wandert in draw.z): entwertet das
       Sichtbarkeits-Bit eines Slots bei Wiederverwendung — sonst erbt ein frisch
       registriertes Mesh das „verdeckt"-Verdikt seines Vorgängers (1-Frame-Löcher bei
       jedem LOD-Remesh); Gen-Mismatch zählt als sichtbar. */
    private int[][] slotGen = new int[DESC_KINDS][];
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
    /* Sichtbarkeits-Bit (gen<<24 | 1 = letzten Frame sichtbar) pro Descriptor-Slot:
       device-lokal und bewusst NICHT geringt — persistiert über Frames. Geschrieben nur vom
       Phase-2-Compute und nur von der ZULÄSSIGEN Invocation eines Slots (Level/Gate passt);
       die per-Level-LOD-Dispatches besuchen jeden LOD-Slot mehrfach. */
    private int visBuffer;
    /* Statistik-Rückweg: GPU kopiert die Counts in diesen persistent-READ-gemappten Ring;
       die CPU liest nach dem Frame-Fence direkt aus dem Mapping — KEIN glGetBufferSubData
       (das ließ den Treiber den Count-Buffer jeden Frame VIDEO<->HOST verschieben und
       stallte bei Upload-Bursts sekundenlang: die 62-99-ms-Spikes). */
    private int countReadBuffer;
    private java.nio.ByteBuffer countReadMapped;
    private long cmdSlotBytes, offSlotBytes;
    /* Two-Phase: Byte-/Uint-Größe EINES Phasen-Bereichs im Command-Slot (Phase 2 liegt um
       cmdPhaseBytes hinter Phase 1 — getrennte Ranges, kein Hazard mit Phase-1-Draws). */
    private long cmdPhaseBytes;
    private int cmdPhaseUints;
    private final long[] segCmdBase = new long[SEGMENTS];   // Byte-Basen im Phasen-Bereich (20B-Raster)
    private final long[] segOffBase = new long[SEGMENTS];
    private final int[] segCapacity = new int[SEGMENTS];

    private int descCapacity;       // Slots je Descriptor-Art (einheitlich, Wachstum verdoppelt)

    private final Vector4f planeScratch = new Vector4f();
    private final float[] planes = new float[24];
    private final int[] countScratch = new int[2 * SEGMENTS];

    /* Zwischen Phase 1 und 2 eingefrorene Descriptor-Zahlen: im Render-Thread mutiert
       zwischen den Dispatches nachweislich nichts — Versicherung + dokumentierte Invariante
       (beide Phasen müssen exakt dieselben Slot-Ranges bearbeiten). */
    private final int[] phase1Count = new int[DESC_KINDS];
    private int phase1Version;

    @SuppressWarnings("unchecked")
    public GpuCull() {
        this.freeSlots = new ArrayDeque[DESC_KINDS];
        for (int k = 0; k < DESC_KINDS; k++) this.freeSlots[k] = new ArrayDeque<>();
        /* Spiegel immer anlegen: die Descriptor-Pflege läuft auch ohne GPU-Support/bei
           ENABLED=false weiter (billig) — der Laufzeit-Toggle greift dadurch sofort. */
        this.descCapacity = 8192;
        for (int k = 0; k < DESC_KINDS; k++) {
            this.mirror[k] = new int[this.descCapacity * DESC_INTS];
            this.slotGen[k] = new int[this.descCapacity];
        }
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
        this.locCullOcclusion = this.compute.getUniformLocation("u_OcclusionEnabled");
        this.locCullHiZ = this.compute.getUniformLocation("u_HiZ");
        this.locCullViewProj = this.compute.getUniformLocation("u_ViewProj");
        this.locCullHiZMips = this.compute.getUniformLocation("u_HiZMips");
        this.locVisBase = this.compute.getUniformLocation("u_VisBase");
        this.locCullDebug = this.compute.getUniformLocation("u_CullDebug");
        this.locPhase = this.compute.getUniformLocation("u_Phase");
        /* u_HiZ einmalig auf Unit 2 (Sampler-Default 0 zeigt sonst aufs TextureArray —
           sampler2D vs. 2D_ARRAY, schlafendes UB in Frames ohne Occlusion). */
        this.compute.bind();
        this.compute.setUniformi(this.locCullHiZ, 2);
        this.compute.unbind();

        this.hiZCopy = new ShaderProgram(new Shader(HIZ_COPY_SOURCE, ShaderType.COMPUTE));
        this.locCopyDepth = this.hiZCopy.getUniformLocation("u_Depth");
        this.hiZReduce = new ShaderProgram(new Shader(HIZ_REDUCE_SOURCE, ShaderType.COMPUTE));
        this.locReduceSrcLevel = this.hiZReduce.getUniformLocation("u_SrcLevel");
        /* Beide Pyramiden-Sampler lesen von Textur-Unit 2 (Unit 0 gehört dem TextureArray). */
        this.hiZReduce.bind();
        this.hiZReduce.setUniformi("u_Src", 2);
        this.hiZReduce.unbind();

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
        /* Two-Phase: pro Frame-Slot liegen ZWEI komplette Command-Bereiche hintereinander
           (Phase 1 = Letzte-Frame-Sichtbare, Phase 2 = Nachzügler) — getrennte Ranges statt
           In-Place-Überschreiben, damit die Phase-2-Writes nie mit den noch lesenden
           Phase-1-Draws kollidieren. Offsets teilen sich beide Phasen (identische Werte). */
        this.cmdPhaseBytes = cmd;
        this.cmdPhaseUints = (int) (cmd / Integer.BYTES);
        this.cmdSlotBytes = MappedRing.align(2 * cmd);
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

        this.visBuffer = GL15.glGenBuffers();
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, this.visBuffer);
        GL44.glBufferStorage(GL43.GL_SHADER_STORAGE_BUFFER,
                (long) DESC_KINDS * this.descCapacity * Integer.BYTES, 0);
        /* BufferStorage-Inhalt ist undefiniert -> auf 0 klaren. Generation 0 passt zu keiner
           Slot-Generation (startet bei 1) -> Gen-Mismatch = „sichtbar" = korrektes
           Kaltstart-Verhalten (alles läuft über Phase 1, kein Pop-in). */
        GL43.glClearBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER, GL30.GL_R32UI,
                0, (long) DESC_KINDS * this.descCapacity * Integer.BYTES,
                GL30.GL_RED_INTEGER, GL11.GL_UNSIGNED_INT, (IntBuffer) null);
        GlDebug.labelBuffer(this.visBuffer, "GpuCull Sichtbarkeits-Bits");

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
        int gen = (this.slotGen[kind][slot] + 1) & 0xFF;
        this.slotGen[kind][slot] = gen;
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
        m[i + 10] = gen;                        // Slot-Generation (Vis-Bit-Tag)
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
            int[] grownGen = new int[newCapacity];
            System.arraycopy(this.slotGen[k], 0, grownGen, 0, this.slotGen[k].length);
            this.slotGen[k] = grownGen;
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
        GL15.glDeleteBuffers(this.visBuffer);       // Vis-Bits starten neu bei 0 = sichtbar (harmlos)
        this.createOutputBuffers();
        this.logger.debug("GPU-Cull: Descriptor-Kapazität gewachsen auf " + newCapacity);
    }

    /* ------------------------- Frame-Pfad ------------------------- */

    /**
     * Phase 1 des Two-Phase-Cull: kopiert die CPU-Spiegel als konsistenten Snapshot in den
     * Frame-Slot (nur bei Änderung seit dem letzten Besuch dieses Slots) und lässt den
     * Compute-Pass die Letzte-Frame-Sichtbaren (Vis-Bit) frustum-gefiltert in den
     * Phase-1-Command-Bereich kompaktieren. Aufruf VOR den Phase-1-Draws; der Aufrufer setzt
     * danach die Memory-Barrier. NUR diese Phase kopiert den Snapshot und cleart die Counts.
     */
    public void dispatchPhase1(int frameSlot, Camera camera) {
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

        /* Count-Slots BEIDER Phasen nullen (device-local, timeline-geordnet; null-Data = 0). */
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, this.countBuffer);
        GL43.glClearBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER, GL30.GL_R32UI,
                (long) frameSlot * COUNT_SLOT_BYTES, (long) 2 * SEGMENTS * Integer.BYTES,
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
        this.compute.setUniformi(this.locPhase, 0);

        /* Descriptor-Zahlen für Phase 2 einfrieren (beide Phasen bearbeiten dieselben Ranges). */
        this.phase1Version = this.version;
        for (int k = 0; k < DESC_KINDS; k++) this.phase1Count[k] = this.mirrorCount[k];

        this.bindOutputs(frameSlot);
        this.dispatchAllKinds(frameSlot, 0);
        this.compute.unbind();
    }

    /**
     * Phase 2: testet ALLE Descriptoren gegen die in DIESEM Frame gebaute Pyramide (aktuelle
     * Matrix/Kamera — keine Latenz, deshalb auch kein Kamera-Sprung-Guard mehr), aktualisiert
     * die Vis-Bits und kompaktiert NUR die Nachzügler (jetzt sichtbar, aber nicht in Phase 1
     * gezeichnet) in den Phase-2-Command-Bereich. Ohne gültige Pyramide (MSAA/kein FBO)
     * fail-open: Frustum-Sichtbare außerhalb von Phase 1 werden ungetestet gezeichnet.
     * KEIN Snapshot-Copy/Count-Clear — das hat Phase 1 erledigt; ein erneuter Snapshot würde
     * den Ring-Slot zerreißen, den der noch nicht ausgeführte Phase-1-Compute liest.
     */
    public void dispatchPhase2(int frameSlot, Camera camera) {
        if (this.version != this.phase1Version) {
            this.logger.debug("GPU-Cull: Descriptor-Mutation zwischen Phase 1 und 2 (Version "
                    + this.phase1Version + " -> " + this.version + ")");
        }
        boolean occlusion = this.hiZValid;
        if (occlusion) {
            this.statActive++;
        } else {
            this.statInactive++;
        }

        this.compute.bind();
        /* Planes/Kamera-Uniforms sind Programm-State und seit Phase 1 unverändert gültig
           (gleiche Kamera, gleiches Programm — dazwischen liefen nur andere Programme). */
        this.compute.setUniformi(this.locPhase, 1);
        this.compute.setUniformi(this.locCullOcclusion, occlusion ? 1 : 0);
        this.compute.setUniformi(this.locCullDebug, DEBUG_TINT ? 1 : 0);
        if (occlusion) {
            GL13.glActiveTexture(GL13.GL_TEXTURE2);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, this.hiZTexture);
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            this.compute.setUniformi(this.locCullHiZMips, this.hiZMips);
            this.compute.setUniformMatrix4f(this.locCullViewProj, camera.getProjectionViewMatrix());
        }

        this.bindOutputs(frameSlot);
        this.dispatchAllKinds(frameSlot, 1);
        this.compute.unbind();
    }

    /** SSBO-Bindings der Ausgabe-/Zustandspuffer eines Frame-Slots (für beide Phasen gleich). */
    private void bindOutputs(int frameSlot) {
        GL30.glBindBufferRange(GL43.GL_SHADER_STORAGE_BUFFER, 5, this.cmdBuffer,
                (long) frameSlot * this.cmdSlotBytes, this.cmdSlotBytes);
        GL30.glBindBufferRange(GL43.GL_SHADER_STORAGE_BUFFER, 6, this.offBuffer,
                (long) frameSlot * this.offSlotBytes, this.offSlotBytes);
        GL30.glBindBufferRange(GL43.GL_SHADER_STORAGE_BUFFER, 7, this.countBuffer,
                (long) frameSlot * COUNT_SLOT_BYTES, (long) 2 * SEGMENTS * Integer.BYTES);
        GL30.glBindBufferRange(GL43.GL_SHADER_STORAGE_BUFFER, 8, this.gateRing.getBuffer(),
                this.gateRing.slotOffset(frameSlot), MappedRing.align((long) this.gateMirror.length * Integer.BYTES));
        GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 9, this.visBuffer);
    }

    /** Alle Segment-Dispatches einer Phase (eingefrorene Counts aus Phase 1). */
    private void dispatchAllKinds(int frameSlot, int phase) {
        /* Sections: OPAQUE + CUTOUT (Level-Filter -1 = Sicht-Gate aktiv). */
        this.dispatchKind(frameSlot, SEG_OPAQUE, 0, this.phase1Count[0], -1, phase);
        this.dispatchKind(frameSlot, SEG_CUTOUT, 1, this.phase1Count[1], -1, phase);
        /* LOD: ein Dispatch pro Level in ein eigenes Segment (per-Level-GPU-Queries + Skip). */
        for (int level = 1; level <= MAX_LOD_LEVELS; level++) {
            if (this.lodLevelCount[level] == 0) continue;
            this.dispatchKind(frameSlot, SEG_LOD_BASE + level - 1, DESC_LOD, this.phase1Count[DESC_LOD], level, phase);
        }
    }

    private void dispatchKind(int frameSlot, int segment, int kind, int count, int levelFilter, int phase) {
        if (count == 0) return;
        GL30.glBindBufferRange(GL43.GL_SHADER_STORAGE_BUFFER, 4, this.descRing.getBuffer(),
                this.descRing.slotOffset(frameSlot) + (long) kind * this.descCapacity * DESC_INTS * Integer.BYTES,
                (long) this.descCapacity * DESC_INTS * Integer.BYTES);
        this.compute.setUniformi(this.locCount, count);
        this.compute.setUniformi(this.locLevelFilter, levelFilter);
        /* u_CmdBase in UINT-Einheiten (der Shader indiziert ein uint-Array) inkl. Phasen-
           Bereich, u_OffBase in vec4s (Offsets phasen-geteilt). */
        this.compute.setUniformi(this.locCmdBase,
                (int) (this.segCmdBase[segment] / Integer.BYTES) + phase * this.cmdPhaseUints);
        this.compute.setUniformi(this.locOffBase, (int) (this.segOffBase[segment] / OFFSET_BYTES));
        this.compute.setUniformi(this.locCountIndex, segment + phase * SEGMENTS);
        this.compute.setUniformi(this.locVisBase, kind * this.descCapacity);
        GL43.glDispatchCompute((count + 255) / 256, 1, 1);
    }

    /** Nach den Dispatches einer Phase, vor deren Draws: Commands/Offsets sichtbar machen. */
    public void barrier() {
        GL42.glMemoryBarrier(GL43.GL_COMMAND_BARRIER_BIT | GL43.GL_SHADER_STORAGE_BARRIER_BIT
                | GL42.GL_BUFFER_UPDATE_BARRIER_BIT);
    }

    /**
     * Nach der Phase-2-Barrier: Statistik-Copy (GPU-seitig, pipelined) der Counts BEIDER
     * Phasen in den Read-Ring — gelesen wird erst nach dem Frame-Fence direkt aus dem Mapping.
     */
    public void copyCounts() {
        GL15.glBindBuffer(GL31.GL_COPY_READ_BUFFER, this.countBuffer);
        GL15.glBindBuffer(GL31.GL_COPY_WRITE_BUFFER, this.countReadBuffer);
        GL31.glCopyBufferSubData(GL31.GL_COPY_READ_BUFFER, GL31.GL_COPY_WRITE_BUFFER,
                (long) this.lastDispatchSlot * COUNT_SLOT_BYTES,
                (long) this.lastDispatchSlot * COUNT_SLOT_BYTES, (long) 2 * SEGMENTS * Integer.BYTES);
        GL15.glBindBuffer(GL31.GL_COPY_READ_BUFFER, 0);
        GL15.glBindBuffer(GL31.GL_COPY_WRITE_BUFFER, 0);
    }

    private int lastDispatchSlot;

    /* ------------------------- Hi-Z-Occlusion (P4) ------------------------- */

    /* R32F-Pyramide über dem Phase-1-Depth DIESES Frames (Reversed-Z ⇒ MIN-Reduktion:
       konservativ hält jede Zelle den am weitesten entfernten Wert ihres Fußabdrucks).
       Der Phase-2-Compute testet AABBs mit der aktuellen Matrix/Kamera — keine Latenz;
       Rand-/Near-/Nahring-Guards im Shader bleiben als Konservativismen. */
    private ShaderProgram hiZCopy, hiZReduce;
    private int hiZTexture;
    /* Eigene Depth-KOPIE (Blit-Ziel): die Pyramide darf NIE das Depth-Attachment des noch
       gebundenen Szene-FBO sampeln — das ist undefiniertes Verhalten (Feedback-Grauzone)
       und war die Ursache des schnellen Küsten-Flackerns. Der Blit synchronisiert zugleich
       die ausstehenden Depth-Writes des Opaque-Passes. */
    private int hiZDepthCopy, hiZBlitFbo;
    private int hiZWidth, hiZHeight, hiZMips;
    private boolean hiZValid;
    private int locCullOcclusion, locCullHiZ, locCullViewProj, locCullHiZMips,
            locVisBase, locCullDebug;
    private int locCopyDepth;
    private int locReduceSrcLevel;

    /* Telemetrie (1-s-Fenster, s. telemetrieZeileUndReset): WARUM war die Occlusion je Frame
       (in)aktiv? (keinDepth = MSAA/kein Depth-Texture-Pfad, keinFbo = Default-Framebuffer). */
    private int statActive, statInactive, statNoDepth, statNoFbo;

    /**
     * Baut die Depth-Pyramide aus dem Phase-1-Depth (Aufruf NACH den Phase-1-Draws, VOR
     * {@link #dispatchPhase2}; {@code depthTexture} 0 = kein Depth-Texture-Pfad, z.B. MSAA
     * an → Occlusion aus, Phase 2 läuft fail-open). Läuft GPU-seitig pipelined.
     */
    public void buildPyramid(int depthTexture, int width, int height) {
        if (!this.isActive()) {
            this.hiZValid = false;
            return;
        }
        if (depthTexture == 0 || width <= 0 || height <= 0) {
            this.statNoDepth++;
            this.hiZValid = false;
            return;
        }
        if (this.hiZTexture == 0 || this.hiZWidth != width || this.hiZHeight != height) {
            this.createHiZTexture(width, height);
        }

        /* Depth des gebundenen Szene-FBO in die eigene Kopie blitten (definiertes Verhalten;
           Formate identisch DEPTH_COMPONENT32F). Danach Bindings zurück auf das Szene-FBO.
           Guard: Default-Framebuffer (0) hat 24-Bit-Depth -> Blit wäre GL_INVALID_OPERATION
           („Depth formats do not match", im allerersten Frame beobachtet). */
        int sceneFbo = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        if (sceneFbo == 0) {
            this.statNoFbo++;
            this.hiZValid = false;
            return;
        }
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, sceneFbo);
        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, this.hiZBlitFbo);
        GL30.glBlitFramebuffer(0, 0, width, height, 0, 0, width, height,
                GL11.GL_DEPTH_BUFFER_BIT, GL11.GL_NEAREST);
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, sceneFbo);

        /* Mip 0: Depth-KOPIE -> R32F. */
        this.hiZCopy.bind();
        GL13.glActiveTexture(GL13.GL_TEXTURE2);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, this.hiZDepthCopy);
        this.hiZCopy.setUniformi(this.locCopyDepth, 2);
        GL42.glBindImageTexture(0, this.hiZTexture, 0, false, 0, GL15.GL_WRITE_ONLY, GL30.GL_R32F);
        GL43.glDispatchCompute((width + 15) / 16, (height + 15) / 16, 1);

        /* Reduktion: Level für Level MIN aus 2×2 (+ Rand-Klemmung bei ungeraden Größen). */
        this.hiZReduce.bind();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, this.hiZTexture);
        int w = width, h = height;
        for (int level = 1; level < this.hiZMips; level++) {
            GL42.glMemoryBarrier(GL42.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT | GL43.GL_TEXTURE_FETCH_BARRIER_BIT);
            w = Math.max(1, w / 2);
            h = Math.max(1, h / 2);
            this.hiZReduce.setUniformi(this.locReduceSrcLevel, level - 1);
            GL42.glBindImageTexture(0, this.hiZTexture, level, false, 0, GL15.GL_WRITE_ONLY, GL30.GL_R32F);
            GL43.glDispatchCompute((w + 15) / 16, (h + 15) / 16, 1);
        }
        this.hiZReduce.unbind();
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GL42.glMemoryBarrier(GL43.GL_TEXTURE_FETCH_BARRIER_BIT);
        this.hiZValid = true;
    }

    private void createHiZTexture(int width, int height) {
        if (this.hiZTexture != 0) GL11.glDeleteTextures(this.hiZTexture);
        if (this.hiZDepthCopy != 0) GL11.glDeleteTextures(this.hiZDepthCopy);
        if (this.hiZBlitFbo != 0) GL30.glDeleteFramebuffers(this.hiZBlitFbo);
        this.hiZWidth = width;
        this.hiZHeight = height;
        this.hiZMips = 32 - Integer.numberOfLeadingZeros(Math.max(width, height));
        this.hiZTexture = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, this.hiZTexture);
        GL42.glTexStorage2D(GL11.GL_TEXTURE_2D, this.hiZMips, GL30.GL_R32F, width, height);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST_MIPMAP_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        GlDebug.labelTexture(this.hiZTexture, "GpuCull Hi-Z-Pyramide");

        /* Depth-Kopie + Blit-FBO (depth-only, Draw-/Read-Buffer NONE für Completeness). */
        this.hiZDepthCopy = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, this.hiZDepthCopy);
        GL42.glTexStorage2D(GL11.GL_TEXTURE_2D, 1, GL30.GL_DEPTH_COMPONENT32F, width, height);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL14.GL_TEXTURE_COMPARE_MODE, GL11.GL_NONE);
        GlDebug.labelTexture(this.hiZDepthCopy, "GpuCull Depth-Kopie");
        this.hiZBlitFbo = GL30.glGenFramebuffers();
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, this.hiZBlitFbo);
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT,
                GL11.GL_TEXTURE_2D, this.hiZDepthCopy, 0);
        GL11.glDrawBuffer(GL11.GL_NONE);
        GL11.glReadBuffer(GL11.GL_NONE);
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
        this.hiZValid = false;
    }

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

    public long commandOffset(int frameSlot, int segment, int phase) {
        return frameSlot * this.cmdSlotBytes + phase * this.cmdPhaseBytes + this.segCmdBase[segment];
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
    /** 1-s-Telemetrie: warum war die Occlusion je Frame (in)aktiv? null wenn keine Frames. */
    public String telemetrieZeileUndReset() {
        int gesamt = this.statActive + this.statInactive;
        if (gesamt == 0) return null;
        String line = "GpuCull-Occlusion: aktiv %d/%d (inaktiv: keinDepth %d, keinFbo %d)".formatted(
                this.statActive, gesamt, this.statNoDepth, this.statNoFbo);
        this.statActive = 0;
        this.statInactive = 0;
        this.statNoDepth = 0;
        this.statNoFbo = 0;
        return line;
    }

    /** Counts beider Phasen: Index [0..SEGMENTS) = Phase 1, [SEGMENTS..2*SEGMENTS) = Phase 2. */
    public int[] readCounts(int frameSlot) {
        int base = frameSlot * COUNT_SLOT_BYTES;
        for (int i = 0; i < 2 * SEGMENTS; i++) {
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
        GL15.glDeleteBuffers(this.visBuffer);
        this.countReadMapped = null;
        if (this.hiZTexture != 0) GL11.glDeleteTextures(this.hiZTexture);
        if (this.hiZDepthCopy != 0) GL11.glDeleteTextures(this.hiZDepthCopy);
        if (this.hiZBlitFbo != 0) GL30.glDeleteFramebuffers(this.hiZBlitFbo);
        if (this.hiZCopy != null) this.hiZCopy.dispose();
        if (this.hiZReduce != null) this.hiZReduce.dispose();
    }

    /* ------------------------- Compute-Shader ------------------------- */

    private static final String COMPUTE_SOURCE = """
            #version 460 core

            layout(local_size_x = 256) in;

            struct DrawDesc {
                ivec4 ipos;    // x=WeltX, y=WeltZ, z=GateSlot|Level, w=Welt-Y des Draw-Offsets
                vec4  bounds;  // x=minY, y=maxY (Welt), z=Kantenlänge XZ, w=Positions-Skala
                uvec4 draw;    // x=indexCount (0=leer), y=baseVertex, z=Slot-Generation
            };

            layout(std430, binding = 4) readonly buffer Descriptors { DrawDesc u_Descs[]; };
            layout(std430, binding = 5) writeonly buffer Commands { uint u_Cmds[]; };
            layout(std430, binding = 6) writeonly buffer Offsets { vec4 u_Offs[]; };
            layout(std430, binding = 7) buffer Counts { uint u_Counts[]; };
            layout(std430, binding = 8) readonly buffer Gate { uint u_Hidden[]; };
            /* Sichtbarkeits-Bit pro Slot: (Generation << 24) | 1 = letzten Frame sichtbar.
               Persistiert ueber Frames (nicht geringt); Gen-Mismatch = neuer Slot = sichtbar.
               Es entscheidet NUR die Phasen-Zuordnung, nie die Sichtbarkeit. */
            layout(std430, binding = 9) buffer Vis { uint u_Vis[]; };

            uniform vec4 u_Planes[6];
            uniform ivec3 u_CamBlock;
            uniform vec3 u_CamFrac;
            uniform int u_Count;
            uniform int u_LevelFilter; // -1 = Sections (Sicht-Gate aktiv), sonst LOD-Level
            uniform int u_CmdBase;     // in uints (Phasen-Bereich bereits eingerechnet)
            uniform int u_OffBase;     // in vec4s (phasen-geteilt)
            uniform int u_CountIndex;  // Segment + Phase*SEGMENTS

            /* Two-Phase-Occlusion: Phase 0 zeichnet die Letzte-Frame-Sichtbaren (kein Hi-Z),
               Phase 1 testet gegen die aus Phase 0 gebaute SAME-FRAME-Pyramide, aktualisiert
               das Vis-Bit und zeichnet Nachzuegler. Damit ist die Pyramide am Frame-Ende
               immer vollstaendig — der Selbst-Feedback-Loop des Ein-Phasen-Hi-Z (gecullte
               Objekte machten sich durch ihre eigene Abwesenheit wieder sichtbar) entfaellt. */
            uniform int u_Phase;
            uniform int u_OcclusionEnabled;
            uniform sampler2D u_HiZ;
            uniform mat4 u_ViewProj;  // AKTUELLE kamerarelative ProjectionView (wie die Draws)
            uniform int u_HiZMips;
            uniform int u_VisBase;    // Basis der Descriptor-Art im Vis-Buffer
            uniform int u_CullDebug;  // 1 = Verdeckt-Verdikte rot zeichnen statt cullen

            /* true = AABB ist durch die Phase-0-Tiefe DIESES Frames sicher verdeckt.
               Konservativ: Naeher-Ring, Near-Plane-Schnitt und Bildrand-Beruehrung gelten
               als sichtbar. Reversed-Z: Objekt-Naechstpunkt (max ndc.z) < MIN-Pyramidenwert
               => verdeckt. */
            bool istVerdeckt(vec3 mnIn, vec3 mxIn) {
                /* AABB konservativ um 0.5 Bloecke aufblasen: entschaerft Koplanar-Grenzfaelle
                   (Oberflaeche des Objekts == Pyramiden-Tiefe) gegen Subpixel-/TAA-Jitter. */
                vec3 mn = mnIn - 0.5;
                vec3 mx = mxIn + 0.5;

                /* Naeher-Ring immer zeichnen (Praezisions-Puffer). */
                vec2 zentrum = 0.5 * (mn.xz + mx.xz);
                if (dot(zentrum, zentrum) < 96.0 * 96.0) return false;

                vec2 uvMin = vec2(2.0);
                vec2 uvMax = vec2(-1.0);
                float maxZ = 0.0;
                for (int c = 0; c < 8; c++) {
                    vec3 ecke = vec3((c & 1) != 0 ? mx.x : mn.x,
                                     (c & 2) != 0 ? mx.y : mn.y,
                                     (c & 4) != 0 ? mx.z : mn.z);
                    vec4 clip = u_ViewProj * vec4(ecke, 1.0);
                    if (clip.w < 0.05) return false; // hinter/nahe der Kamera -> sichtbar
                    vec2 uv = (clip.xy / clip.w) * 0.5 + 0.5;
                    uvMin = min(uvMin, uv);
                    uvMax = max(uvMax, uv);
                    maxZ = max(maxZ, clip.z / clip.w);
                }
                /* Raster-Rand: das analytische Rect um 1 Pixel erweitern — die Rasterisierung
                   deckt nur Sample-ZENTREN, die exakte Rect-Kante kann im nicht gesampelten
                   Nachbar-Texel liegen (die 0.5-Block-Inflation ist auf LOD-Distanz weit
                   unter einem Pixel und schuetzt dagegen nicht). Die Level-Wahl unten
                   rechnet mit dem erweiterten Rect -> 4-Ecken-Abdeckung bleibt garantiert. */
                vec2 groesse = vec2(textureSize(u_HiZ, 0));
                uvMin -= 1.0 / groesse;
                uvMax += 1.0 / groesse;
                /* Bildrand beruehrt: keine gueltige Tiefe dahinter -> sichtbar. */
                if (uvMin.x < 0.0 || uvMin.y < 0.0 || uvMax.x > 1.0 || uvMax.y > 1.0) return false;

                vec2 rectPx = (uvMax - uvMin) * groesse;
                int level = clamp(int(ceil(log2(max(rectPx.x, rectPx.y) + 1.0))), 0, u_HiZMips - 1);
                float d0 = textureLod(u_HiZ, uvMin, float(level)).r;
                float d1 = textureLod(u_HiZ, vec2(uvMax.x, uvMin.y), float(level)).r;
                float d2 = textureLod(u_HiZ, vec2(uvMin.x, uvMax.y), float(level)).r;
                float d3 = textureLod(u_HiZ, uvMax, float(level)).r;
                float minTiefe = min(min(d0, d1), min(d2, d3));
                return maxZ < minTiefe;
            }

            void main() {
                uint i = gl_GlobalInvocationID.x;
                if (i >= uint(u_Count)) return;
                uint ci = uint(u_CmdBase) + i * 5u;
                DrawDesc d = u_Descs[i];

                /* zulaessig = lebt und gehoert zu diesem Dispatch (Level bzw. Gate offen). */
                bool zulaessig = d.draw.x != 0u;
                if (zulaessig) {
                    if (u_LevelFilter >= 0) {
                        zulaessig = d.ipos.z == u_LevelFilter;
                    } else {
                        /* Spalte von LOD verdeckt (atomarer Swap wie im CPU-Pfad) */
                        zulaessig = u_Hidden[d.ipos.z] == 0u;
                    }
                }

                /* Kamerarelatives AABB: XZ über exakte int-Differenz (Welt-Koordinaten als int,
                   Kamera-Anker als Block + Bruchteil — float-Präzisions-Falle der Roadmap). */
                float camY = float(u_CamBlock.y) + u_CamFrac.y;
                float ox = float(d.ipos.x - u_CamBlock.x) - u_CamFrac.x;
                float oz = float(d.ipos.y - u_CamBlock.z) - u_CamFrac.z;
                /* Offset IMMER schreiben (beide Phasen teilen den Bereich, Wert ist pro Slot
                   deterministisch — auch fuer gecullte Slots harmlos, Command ist dann null). */
                u_Offs[uint(u_OffBase) + i] = vec4(ox, float(d.ipos.w) - camY, oz, d.bounds.w);

                vec3 mn = vec3(ox, d.bounds.x - camY, oz);
                vec3 mx = vec3(ox + d.bounds.z, d.bounds.y - camY, oz + d.bounds.z);
                bool imFrustum = zulaessig;
                for (int p = 0; p < 6 && imFrustum; p++) {
                    vec4 pl = u_Planes[p];
                    vec3 v = vec3(pl.x >= 0.0 ? mx.x : mn.x,
                                  pl.y >= 0.0 ? mx.y : mn.y,
                                  pl.z >= 0.0 ? mx.z : mn.z);
                    imFrustum = dot(pl.xyz, v) + pl.w >= 0.0;
                }

                /* Vis-Bit lesen: Gen-Mismatch (frisch registrierter Slot, z.B. LOD-Remesh)
                   zaehlt als sichtbar — neue Meshes erscheinen ohne Pop-in in Phase 0. */
                uint si = uint(u_VisBase) + i;
                uint gen = d.draw.z & 0xFFu;
                uint alt = u_Vis[si];
                bool letzteSichtbar = (alt >> 24) != gen || (alt & 1u) != 0u;

                bool zeichnen;
                uint basisInstanz = 0u;
                if (u_Phase == 0) {
                    /* Phase 0: Letzte-Frame-Sichtbare zeichnen (kein Hi-Z, kein Vis-Write). */
                    zeichnen = imFrustum && letzteSichtbar;
                } else {
                    /* Phase 1: Verdeckungstest gegen die Same-Frame-Pyramide. Vis-Write NUR
                       fuer zulaessige Invocations — die per-Level-LOD-Dispatches besuchen
                       jeden LOD-Slot mehrfach, ein level-fremder Write wuerde das echte
                       Verdikt ueberschreiben. visBit=0 NUR bei echtem bestandenem Verdikt,
                       alles andere fail-open sichtbar (Frustum-Rueckkehrer kommen so ueber
                       Phase 0 herein, kein Nachzuegler-Burst beim Umschauen). */
                    bool verdeckt = imFrustum && u_OcclusionEnabled != 0 && istVerdeckt(mn, mx);
                    if (zulaessig) u_Vis[si] = (gen << 24) | (verdeckt ? 0u : 1u);
                    zeichnen = imFrustum && !letzteSichtbar && (!verdeckt || u_CullDebug != 0);
                    if (zeichnen && verdeckt) basisInstanz = 1u; // Debug: Vertex-Shader tintet rot
                }

                /* Gecullt/fremd: Null-Command schreiben (count=0), NICHT einfach return — sonst
                   zeichnen veraltete Slot-Inhalte des Ring-Slots Geister-Geometrie. */
                if (!zeichnen) {
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
                u_Cmds[ci + 4u] = basisInstanz; // 1 = Occlusion-Debug-Markierung (rot)
            }
            """;

    /* Mip 0 der Pyramide: Szenen-Depth (32F) 1:1 in die R32F-Pyramide kopieren. */
    private static final String HIZ_COPY_SOURCE = """
            #version 460 core
            layout(local_size_x = 16, local_size_y = 16) in;
            uniform sampler2D u_Depth;
            layout(r32f, binding = 0) writeonly uniform image2D u_Dst;
            void main() {
                ivec2 p = ivec2(gl_GlobalInvocationID.xy);
                ivec2 size = imageSize(u_Dst);
                if (p.x >= size.x || p.y >= size.y) return;
                imageStore(u_Dst, p, vec4(texelFetch(u_Depth, p, 0).r));
            }
            """;

    /* Eine Pyramiden-Stufe: MIN aus 2x2 des Quell-Levels (Reversed-Z: MIN = am weitesten
       entfernt = konservativ fuer den Verdeckungstest). Bei UNGERADEN Quellgroessen wird die
       letzte Spalte/Zeile explizit in die MIN einbezogen — reine Klemmung liess sie frueher
       aus der Reduktion fallen (falsche Culls an Silhouette/Horizont, LOD-Loecher). */
    private static final String HIZ_REDUCE_SOURCE = """
            #version 460 core
            layout(local_size_x = 16, local_size_y = 16) in;
            uniform sampler2D u_Src;
            uniform int u_SrcLevel;
            layout(r32f, binding = 0) writeonly uniform image2D u_Dst;

            float fetchMin(ivec2 c, ivec2 srcSize) {
                return texelFetch(u_Src, min(c, srcSize - 1), u_SrcLevel).r;
            }

            void main() {
                ivec2 p = ivec2(gl_GlobalInvocationID.xy);
                ivec2 dstSize = imageSize(u_Dst);
                if (p.x >= dstSize.x || p.y >= dstSize.y) return;
                ivec2 srcSize = textureSize(u_Src, u_SrcLevel);
                ivec2 s = p * 2;
                float d = fetchMin(s, srcSize);
                d = min(d, fetchMin(s + ivec2(1, 0), srcSize));
                d = min(d, fetchMin(s + ivec2(0, 1), srcSize));
                d = min(d, fetchMin(s + ivec2(1, 1), srcSize));
                /* UNGERADE Quellgroesse: dstSize = srcSize/2 laesst die letzte Spalte/Zeile
                   sonst von KEINEM Dst-Texel abgedeckt — ihr Inhalt (am oberen/rechten
                   Bildrand oft Himmel = fern) fiele aus der MIN-Reduktion, der Mip
                   behauptete dort NAEHERE Tiefe als real -> falsche Occlusion-Culls an
                   Silhouetten/Horizont (die beobachteten LOD-Loecher). Die dritte
                   Spalte/Zeile mitnehmen (Klemmung macht das fuer innere Texel neutral). */
                bool oddX = (srcSize.x & 1) != 0;
                bool oddY = (srcSize.y & 1) != 0;
                if (oddX) {
                    d = min(d, fetchMin(s + ivec2(2, 0), srcSize));
                    d = min(d, fetchMin(s + ivec2(2, 1), srcSize));
                }
                if (oddY) {
                    d = min(d, fetchMin(s + ivec2(0, 2), srcSize));
                    d = min(d, fetchMin(s + ivec2(1, 2), srcSize));
                }
                if (oddX && oddY) d = min(d, fetchMin(s + ivec2(2, 2), srcSize));
                imageStore(u_Dst, p, vec4(d));
            }
            """;
}
