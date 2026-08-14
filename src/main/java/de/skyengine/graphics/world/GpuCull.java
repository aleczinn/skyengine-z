package de.skyengine.graphics.world;

import de.skyengine.game.world.chunk.ChunkMesher;
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
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL31;
import org.lwjgl.opengl.GL42;
import org.lwjgl.opengl.GL43;
import org.lwjgl.opengl.GL44;
import org.lwjgl.opengl.GL45;

import java.nio.IntBuffer;
import java.util.ArrayDeque;
import java.util.Arrays;

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
     * <p>DEFAULT AUS (User-Entscheidung 2026-07-29, s. Kosten-Realität unten — bei heutigem
     * unbeleuchtetem Content ist der CPU-Cull schneller). Frustum-Teil ist nach der Spike-Diagnose
     * paritätisch zum CPU-Cull (plain MDI mit Null-Commands + Statistik über den persistent
     * gemappten Read-Ring); die Hi-Z-Occlusion läuft als TWO-PHASE (Roadmap P4): Phase 1
     * zeichnet die Letzte-Frame-Sichtbaren (Vis-Bit pro Descriptor-Slot), daraus wird die
     * Depth-Pyramide DESSELBEN Frames gebaut, Phase 2 testet alle Descriptoren dagegen
     * (aktuelle Matrix/Kamera, keine Latenz) und zeichnet Nachzügler im selben Frame.
     * Kern-Invariante: das Vis-Bit entscheidet nur die PHASEN-ZUORDNUNG, nie die
     * Sichtbarkeit — stale Bits (K-Toggle, Kaltstart, Buffer-Neuaufbau) kosten höchstens
     * einen Frame Phase-2-Umweg, nie ein Loch.
     *
     * <p>KOSTEN-REALITÄT (bewusste Entscheidung, nicht „optimieren bis schneller"): der
     * GPU-Pfad kostet ~0,15–0,2 ms Fixkosten pro Frame, AUFLÖSUNGSUNABHÄNGIG (gemessen
     * 720p 1440→1180 FPS und 5120×1440 880→750 FPS = jeweils +0,15–0,2 ms Frame-Zeit) —
     * das ist die Sync-Struktur (Phase-1-Draws → Pyramide → Phase 2, zweiter Draw-Satz,
     * 4 Barriers), nicht die Pyramiden-Arbeit (Pow2-Viertel-Basis + gefaltete Reduktion,
     * 3 Dispatches). Bei heutigem unbeleuchtetem Content spart die Occlusion fast nichts
     * (Early-Z killt verdeckte Pixel ohnehin) — der Pfad ist also HEUTE langsamer als der
     * CPU-Cull, deshalb Default AUS. Der Pfad bleibt vollständig erhalten und zahlt zurück,
     * sobald Licht-Merge/Schatten-Pass die Frames real verteuern (Schatten-Pass bekommt
     * das Culling gratis) — dann Default wieder AN stellen.
     *
     * <p>Historie: die Ein-Phasen-Vorstufe hatte einen per Telemetrie BEWIESENEN
     * Selbst-Feedback-Loop — gecullte Objekte fehlten in der Pyramide des Folgeframes und
     * wurden durch ihre eigene Abwesenheit wieder sichtbar (LOD-Ring-Flackern in Linien;
     * Draw-Counts oszillierten op 270..336 / L4 24..83 bei statischer Kamera, mit Debug-Rot
     * perfekt stabil). Die 4-Frame-Streak-Hysterese verschob nur die Periode — deshalb
     * Two-Phase statt Hysterese. Werkzeuge: GuiDebugScreen (Optionsmenü) schaltet GPU-Pfad
     * und Debug-Rot, 1-s-Telemetrie „GpuCull-Draws"/„GpuCull-Occlusion" (DebugMode.FULL). */
    public static volatile boolean ENABLED = false;

    /** Occlusion-Debug: Verdeckt-Verdikte werden ROT gezeichnet statt gecullt (GuiDebugScreen). */
    public static volatile boolean DEBUG_TINT = false;

    /**
     * Hi-Z-Occlusion abschalten und NUR das Compute-Frustum fahren (Single-Phase, {@code
     * u_Phase=2}). Der Pfad existierte bisher nur als unfreiwilliger Fallback (MSAA/erster
     * Frame); als Schalter trennt er die Kosten des Compute-Substrats von denen der
     * Occlusion-Maschinerie — Pyramide, Phase-2-Dispatches und der zweite Draw-Satz entfallen
     * komplett, also die halbe Sync-Struktur des Frames.
     *
     * <p>DEFAULT AN (Hi-Z also AUS) nach Messung 2026-07-30, 2560x1440, rd16/lodMax128, feste
     * Pose, RTX 4080: CPU-Cull 980 µs/Frame, GPU-Frustum 1003 µs (+23), GPU-Frustum+Hi-Z
     * 1143 µs (+163). Das Compute-Substrat ist also praktisch gratis, die Occlusion kostet
     * ~140 µs und spart bei heutigem, unbeleuchtetem Content fast nichts (Early-Z verwirft
     * verdeckte Fragmente ohnehin). Wieder einschalten, sobald Fragmente teuer werden
     * (Licht/Schatten) — Schalter liegt im GuiDebugScreen.
     */
    public static volatile boolean FRUSTUM_ONLY = true;




    /* Segmente in Draw-Reihenfolge: Sections OPAQUE, Sections CUTOUT, LOD-Opaque (ALLE Level
       in EINEM Segment). Früher gab es je Level ein eigenes Segment — dadurch besuchte der
       Compute jeden LOD-Slot K-mal pro Phase und jedes Level-Segment submittete die VOLLE
       LOD-Descriptor-Zahl (bei Default-Settings ~20k überflüssige Null-Commands pro Frame,
       bei lodMax=256 ~105k — der Kern der GPU-Pfad-Fixkosten). Der Level steckt weiterhin
       im Descriptor (ipos.z), wird aber für nichts mehr gebraucht.
       Translucent (Sections + LOD) bleibt bewusst CPU (Sortierung bzw. Kleinstmengen). */
    public static final int SEG_OPAQUE = 0;
    public static final int SEG_CUTOUT = 1;
    public static final int SEG_LOD = 2;
    public static final int MAX_LOD_LEVELS = 5;
    public static final int SEGMENTS = 3;

    private static final int SLOTS = 3;                 // muss zu den ChunkRenderer-Fences passen
    private static final int DESC_INTS = 12;            // ivec4 + vec4 + uvec4 (48 Bytes, std430)
    private static final int COMMAND_BYTES = 20;
    private static final int OFFSET_BYTES = 16;
    /* Count-Slot-Stride 256 statt SEGMENTS*4: SSBO-Bind-Offsets müssen das Offset-Alignment
       des Treibers erfüllen (bis 256) — 28-Byte-Schritte wären ab Slot 1 GL_INVALID_VALUE. */
    private static final int COUNT_SLOT_BYTES = MappedRing.ALIGNMENT;

    /* Descriptor-Arrays: 0 = Sections OPAQUE, 1 = Sections CUTOUT, 2 = LOD (alle Level gemischt
       — seit dem Segment-Merge 1:1 das LOD-Segment, kein Level-Filter mehr). */
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

    /* Sicht-Gate: 1 uint pro Chunk-Spalte (1 = von LOD verdeckt, Sections nicht zeichnen). */
    private int[] gateMirror = new int[4096];
    private int gateCount;
    private final ArrayDeque<Integer> gateFree = new ArrayDeque<>();

    private int version = 1;
    private final int[] appliedVersion = new int[SLOTS];

    /* Change-Log der Descriptor-Mutationen (Eintrag = kind<<28 | slot): der Upload in
       dispatchPhase1 schreibt nur noch die seit dem letzten Besuch DIESES Frame-Slots
       geänderten Descriptoren in den Ring (je Slot eigene Log-Position — Slots werden nur
       alle 3 Frames besucht und Descriptor-Slots sofort wiederverwendet, ein globales
       Dirty-Set ließe die anderen Slots stale = Geister-Geometrie). Der frühere Voll-Put
       kopierte bei jeder Mutation den ganzen Spiegel (~600 KB/Frame bei rd=32, bis 3×).
       Läuft das Log über LOG_LIMIT (oder invalidiert growDescriptors die Ring-Slots),
       fällt der nächste Besuch je Slot auf den Voll-Upload zurück (fullUpload). */
    private static final int LOG_LIMIT = 4096;
    private int[] changeLog = new int[1024];
    private int logSize;
    private final int[] logPos = new int[SLOTS];
    private final boolean[] fullUpload = {true, true, true};

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
        this.locReduceLevels = this.hiZReduce.getUniformLocation("u_Levels");
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
                          boolean debugConflict, int indexCount, int baseVertex) {
        return this.addDesc(segKind, blockX, blockZ, gateSlot, originY,
                originY, originY + 32F, 32F, 1F / ChunkMesher.POS_SCALE,
                descriptorDebug(DrawMetadata.SECTION_SCALE_CODE, debugConflict ? 1 : 0),
                indexCount, baseVertex);
    }

    public void removeSection(int segKind, int slot) {
        this.clearDesc(segKind, slot);
    }

    /** Registriert eine LOD-Region (Level 1..5; Superregionen tragen ihre eigene posScale). */
    public int addLod(int level, int blockX, int blockZ, int yBase, float minY, float maxY,
                      float sizeBlocks, float invPosScale, int posScaleCode, int debugConflictMask,
                      int indexCount, int baseVertex) {
        this.lodTotalCount++;
        return this.addDesc(DESC_LOD, blockX, blockZ, level, yBase,
                minY, maxY, sizeBlocks, invPosScale,
                descriptorDebug(posScaleCode, debugConflictMask), indexCount, baseVertex);
    }

    public void removeLod(int level, int slot) {
        this.lodTotalCount--;
        this.clearDesc(DESC_LOD, slot);
    }

    /** true, wenn überhaupt LOD-Descriptoren registriert sind (Skip fürs LOD-Segment). */
    public boolean hasLod() {
        return this.lodTotalCount > 0;
    }

    private int lodTotalCount;

    /** Aktualisiert nur die Debugmaske; Geometrie, Gate und Draw-Daten bleiben unverändert. */
    public void setLodDebugConflict(int slot, int conflictMask) {
        this.setDebugConflict(DESC_LOD, slot, conflictMask);
    }

    /** Markiert einen Section-Draw, falls er trotz LOD-Besitz sichtbar werden sollte. */
    public void setSectionDebugConflict(int segKind, int slot, boolean conflict) {
        this.setDebugConflict(segKind, slot, conflict ? 1 : 0);
    }

    private void setDebugConflict(int kind, int slot, int conflictMask) {
        if (slot < 0) return;
        int i = slot * DESC_INTS + 11;
        int old = this.mirror[kind][i];
        int value = old & 0xFFFF0000 | conflictMask & 0xFFFF;
        if (old == value) return;
        this.mirror[kind][i] = value;
        this.logChange(kind, slot);
        this.version++;
    }

    private static int descriptorDebug(int positionScaleCode, int conflictMask) {
        return (positionScaleCode & 0xF) << 16 | conflictMask & 0xFFFF;
    }

    private int addDesc(int kind, int ix, int iz, int izArg, int iw,
                        float b0, float b1, float b2, float b3, int descriptorDebug,
                        int indexCount, int baseVertex) {
        Integer free = this.freeSlots[kind].poll();
        int slot = free != null ? free : this.mirrorCount[kind];
        if (slot >= this.descCapacity) this.growDescriptors();
        /* max-Regel statt ++: der Trim in clearDesc kann den High-Water-Mark gesenkt haben,
           während höhere Slots noch in der Freelist stehen — ein solcher Slot muss den Mark
           wieder anheben, sonst läge er außerhalb der Dispatch-/Draw-Range. */
        if (slot >= this.mirrorCount[kind]) this.mirrorCount[kind] = slot + 1;

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
        m[i + 11] = descriptorDebug;             // scaleCode<<16 | Debug-Konfliktmaske
        this.logChange(kind, slot);
        this.version++;
        return slot;
    }

    private void clearDesc(int kind, int slot) {
        this.mirror[kind][slot * DESC_INTS + 8] = 0; // indexCount 0 = leerer Slot
        this.freeSlots[kind].add(slot);
        this.logChange(kind, slot);
        /* High-Water-Mark trimmen: freie Slots am Ende abziehen — sonst decken Dispatch und
           Draws für immer den größten je gesehenen Stand (Null-Command-Fetches nach jedem
           Flug). Slots unterhalb des Marks bleiben normal in der Freelist; ein später
           wiederverwendeter Slot OBERHALB hebt den Mark über die max-Regel in addDesc. */
        int m = this.mirrorCount[kind];
        while (m > 0 && this.mirror[kind][(m - 1) * DESC_INTS + 8] == 0) m--;
        this.mirrorCount[kind] = m;
        this.version++;
    }

    /** Descriptor-Mutation ins Change-Log; bei Überlauf Rückfall auf Voll-Upload je Slot. */
    private void logChange(int kind, int slot) {
        if (this.logSize == this.changeLog.length) {
            this.changeLog = Arrays.copyOf(this.changeLog, this.changeLog.length * 2);
        }
        this.changeLog[this.logSize++] = kind << 28 | slot;
        if (this.logSize > LOG_LIMIT) {
            /* Ab hier ist der Voll-Upload billiger als das Replay (z.B. remeshAll, langes
               Spielen mit GPU-Pfad AUS — dispatchPhase1 leert das Log dann nie). */
            Arrays.fill(this.fullUpload, true);
            this.logSize = 0;
            Arrays.fill(this.logPos, 0);
        }
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
        /* Ring-Neuaufbau = alle 3 Slot-Inhalte undefiniert → jeder Slot braucht beim nächsten
           Besuch einen Voll-Upload (das Change-Log deckt nur inkrementelle Änderungen). */
        Arrays.fill(this.fullUpload, true);
        this.logSize = 0;
        Arrays.fill(this.logPos, 0);
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
    public void dispatchPhase1(int frameSlot, Camera camera, boolean singlePhase) {
        this.lastDispatchSlot = frameSlot;
        if (this.appliedVersion[frameSlot] != this.version) {
            this.appliedVersion[frameSlot] = this.version;
            IntBuffer desc = this.descRing.intView(frameSlot);
            if (this.fullUpload[frameSlot]) {
                this.fullUpload[frameSlot] = false;
                for (int k = 0; k < DESC_KINDS; k++) {
                    desc.position(k * this.descCapacity * DESC_INTS);
                    desc.put(this.mirror[k], 0, this.mirrorCount[k] * DESC_INTS);
                }
            } else {
                /* Replay: nur die seit dem letzten Besuch dieses Slots geänderten
                   Descriptoren (Duplikate im Log = harmlose Doppel-Writes). */
                for (int j = this.logPos[frameSlot]; j < this.logSize; j++) {
                    int entry = this.changeLog[j];
                    int kind = entry >>> 28;
                    int slot = entry & 0x0FFFFFFF;
                    desc.position((kind * this.descCapacity + slot) * DESC_INTS);
                    desc.put(this.mirror[kind], slot * DESC_INTS, DESC_INTS);
                }
            }
            this.logPos[frameSlot] = this.logSize;
            desc.position(0);
            /* Gate-Spiegel bleibt ein Voll-Put (≤16 KB — nicht log-würdig). */
            IntBuffer gate = this.gateRing.intView(frameSlot);
            gate.position(0);
            gate.put(this.gateMirror, 0, this.gateCount);
            gate.position(0);

            /* Kompaktierung: haben alle Slots aufgeholt, kann das Log neu beginnen. */
            if (this.logPos[0] == this.logSize && this.logPos[1] == this.logSize
                    && this.logPos[2] == this.logSize) {
                this.logSize = 0;
                Arrays.fill(this.logPos, 0);
            }
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
        /* singlePhase (kein Hi-Z verfügbar: MSAA-Modus, erster Frame): u_Phase=2 zeichnet alle
           Frustum-Sichtbaren direkt im Phase-1-Bereich und setzt die Vis-Bits fail-open —
           Phase 2 samt Barrier und zweitem Draw-Satz entfällt komplett (halber Pfad). */
        this.compute.setUniformi(this.locPhase, singlePhase ? 2 : 0);

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
            /* Unit 2 trägt die Pyramide bereits: buildPyramid bindet sie dort und setzt nur
               die AKTIVE Unit auf 0 zurück — das frühere ActiveTexture/Bind-Trio war redundant. */
            this.compute.setUniformi(this.locCullHiZMips, this.hiZMips);
            this.compute.setUniformMatrix4f(this.locCullViewProj, camera.getProjectionViewMatrix());
        }

        /* Kein bindOutputs: die Bindings 4-9 stehen seit Phase 1 unverändert — die Draws
           dazwischen fassen nur Binding 0 an. */
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
        /* LOD gemergt: EIN Dispatch für alle Level (-2 = kein Filter, kein Sicht-Gate) —
           jeder Slot wird pro Phase genau einmal besucht. */
        if (this.lodTotalCount > 0) {
            this.dispatchKind(frameSlot, SEG_LOD, DESC_LOD, this.phase1Count[DESC_LOD], -2, phase);
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

    /** Nach den Dispatches einer Phase, vor deren Draws: Commands/Offsets sichtbar machen.
     *  (Kein GL_BUFFER_UPDATE_BARRIER_BIT — client-seitig schreibt hier niemand.) */
    public void barrier() {
        GL42.glMemoryBarrier(GL43.GL_COMMAND_BARRIER_BIT | GL43.GL_SHADER_STORAGE_BARRIER_BIT);
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
       Rand-/Near-/Nahring-Guards im Shader bleiben als Konservativismen.

       KOSTEN-DESIGN (5120x1440 kostete die Voll-Kette ~0,5 ms KRITISCHEN PFAD mitten im
       Frame — bei 1,2-ms-Frames der 850→600-FPS-Einbruch; GPU-Auslastung blieb niedrig,
       weil die 13 Mini-Reduce-Dispatches mit je einer Barrier vor allem Pipeline-BLASEN
       erzeugten, keine Arbeit):
       - Basis-Mip ist POW2 bei ~Viertel-Aufloesung (Footprint-MIN direkt aus dem Szenen-
         Depth, lueckenlose Kachelung) → ~16x weniger Pyramiden-Arbeit, und alle weiteren
         Halbierungen sind EXAKT (nie ungerade → der Odd-Size-Verlust der letzten
         Spalte/Zeile, Ursache der LOD-Loch-Linien, ist strukturell unmoeglich).
       - Reduktion GEFALTET: 4 Mip-Stufen pro Dispatch ueber Shared Memory → 3 Dispatches
         und 3 Barriers statt 13 (die Blasen-Quelle).
       - KEIN Voll-Blit mehr: das Szene-FBO wird waehrend des Baus ABGEBUNDEN, dann ist
         das direkte Sampeln seines Depth-Attachments spec-sauber (die Feedback-UB-Falle
         — Ursache des frueheren Kuesten-Flackerns — besteht nur bei GEBUNDENEM FBO;
         Unbind macht die Depth-Writes zugleich sichtbar). */
    private ShaderProgram hiZCopy, hiZReduce;
    private int hiZTexture;
    private int hiZWidth, hiZHeight, hiZMips;      // Basis-Mip-Groesse (Pow2) + Stufenzahl
    private int hiZSrcWidth, hiZSrcHeight;         // Fenstergroesse, fuer die die Basis gilt
    private boolean hiZValid;
    private int locCullOcclusion, locCullHiZ, locCullViewProj, locCullHiZMips,
            locVisBase, locCullDebug;
    private int locCopyDepth;
    private int locReduceSrcLevel, locReduceLevels;

    /* Telemetrie (1-s-Fenster, s. telemetrieZeileUndReset): WARUM war die Occlusion je Frame
       (in)aktiv? (keinDepth = MSAA/kein Depth-Texture-Pfad, keinFbo = Default-Framebuffer). */
    private int statActive, statInactive, statNoDepth, statNoFbo;

    /**
     * Baut die Depth-Pyramide aus dem Phase-1-Depth (Aufruf NACH den Phase-1-Draws, VOR
     * {@link #dispatchPhase2}; {@code depthTexture} 0 = kein Depth-Texture-Pfad, z.B. MSAA
     * an → Occlusion aus, Phase 2 läuft fail-open). Läuft GPU-seitig pipelined.
     */
    public void buildPyramid(int depthTexture, int width, int height, int sceneFbo) {
        if (!this.isActive()) {
            this.hiZValid = false;
            return;
        }
        if (depthTexture == 0 || width <= 0 || height <= 0) {
            this.statNoDepth++;
            this.hiZValid = false;
            return;
        }
        if (this.hiZTexture == 0 || this.hiZSrcWidth != width || this.hiZSrcHeight != height) {
            this.createHiZTexture(width, height);
        }

        /* Guard: Default-Framebuffer = Szene rendert nicht in den FBO, dessen Depth-Textur
           wir gleich sampeln würden (nur im allerersten Frame beobachtet). Die FBO-Id kommt
           vom Aufrufer (Window.getFrameBuffer) — der frühere glGetInteger auf das Binding
           war ein synchroner Treiber-Roundtrip pro Frame. */
        if (sceneFbo == 0) {
            this.statNoFbo++;
            this.hiZValid = false;
            return;
        }
        /* Depth-Writes des Opaque-Passes gegenüber den folgenden Texture-Fetches ordnen.
           Historisch lief das über Abbinden des Szene-FBO (das Depth-Attachment eines
           GEBUNDENEN FBO zu sampeln ist Feedback-UB — die Ursache des Küsten-Flackerns);
           glTextureBarrier erreicht dasselbe ohne Render-Target-Wechsel und ist gemessen
           28 µs/Frame billiger. Weglassen darf man die Ordnung NICHT. */
        GL45.glTextureBarrier();

        /* Basis-Mip (Pow2, ~Viertel-Auflösung): konservatives Footprint-MIN direkt aus dem
           Szenen-Depth — jeder Basis-Texel deckt seinen exakten Pixel-Bereich lückenlos ab. */
        this.hiZCopy.bind();
        GL13.glActiveTexture(GL13.GL_TEXTURE2);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, depthTexture);
        this.hiZCopy.setUniformi(this.locCopyDepth, 2);
        GL42.glBindImageTexture(0, this.hiZTexture, 0, false, 0, GL15.GL_WRITE_ONLY, GL30.GL_R32F);
        GL43.glDispatchCompute((this.hiZWidth + 15) / 16, (this.hiZHeight + 15) / 16, 1);

        /* Gefaltete Reduktion: 4 Mip-Stufen pro Dispatch über Shared Memory (Pow2-Kette,
           exakte Halbierungen) — 3 Dispatches/Barriers statt 13 (Blasen-Quelle). */
        this.hiZReduce.bind();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, this.hiZTexture);
        for (int src = 0; src + 1 < this.hiZMips; src += 4) {
            GL42.glMemoryBarrier(GL42.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT | GL43.GL_TEXTURE_FETCH_BARRIER_BIT);
            int levels = Math.min(4, this.hiZMips - 1 - src);
            this.hiZReduce.setUniformi(this.locReduceSrcLevel, src);
            this.hiZReduce.setUniformi(this.locReduceLevels, levels);
            for (int i = 0; i < 4; i++) {
                int level = src + 1 + Math.min(i, levels - 1);
                GL42.glBindImageTexture(i, this.hiZTexture, level, false, 0, GL15.GL_WRITE_ONLY, GL30.GL_R32F);
            }
            int w1 = Math.max(1, this.hiZWidth >> (src + 1));
            int h1 = Math.max(1, this.hiZHeight >> (src + 1));
            GL43.glDispatchCompute((w1 + 7) / 8, (h1 + 7) / 8, 1);
        }
        this.hiZReduce.unbind();
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GL42.glMemoryBarrier(GL43.GL_TEXTURE_FETCH_BARRIER_BIT);
        this.hiZValid = true;
    }

    private void createHiZTexture(int width, int height) {
        if (this.hiZTexture != 0) GL11.glDeleteTextures(this.hiZTexture);
        this.hiZSrcWidth = width;
        this.hiZSrcHeight = height;
        /* Basis = größte Zweierpotenz ≤ Viertel-Auflösung: Pow2 macht ALLE Halbierungen
           exakt (kein Odd-Size-Verlust möglich), Viertel-Auflösung reicht für Region-/
           Section-AABBs locker (1 Basis-Texel ≈ 4-6 Bildpixel, Test bleibt konservativ). */
        this.hiZWidth = Math.max(1, Integer.highestOneBit(Math.max(1, width / 4)));
        this.hiZHeight = Math.max(1, Integer.highestOneBit(Math.max(1, height / 4)));
        this.hiZMips = 32 - Integer.numberOfLeadingZeros(Math.max(this.hiZWidth, this.hiZHeight));
        this.hiZTexture = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, this.hiZTexture);
        GL42.glTexStorage2D(GL11.GL_TEXTURE_2D, this.hiZMips, GL30.GL_R32F, this.hiZWidth, this.hiZHeight);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST_MIPMAP_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        GlDebug.labelTexture(this.hiZTexture, "GpuCull Hi-Z-Pyramide");
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
     * Index (keine Kompaktierung), gecullte Slots tragen Null-Commands. Liefert bewusst die
     * in Phase 1 EINGEFRORENE Zahl — Draws und Dispatches decken damit exakt dieselben
     * Slot-Ranges, auch wenn zwischendurch (unerwartet) mutiert würde.
     */
    public int drawCount(int segment) {
        if (segment == SEG_OPAQUE) return this.phase1Count[0];
        if (segment == SEG_CUTOUT) return this.phase1Count[1];
        return this.phase1Count[DESC_LOD];
    }

    /**
     * Descriptor-Hochwasserstand je Art für die Telemetrie: er bestimmt sowohl die
     * Dispatch-Range als auch {@link #drawCount} (inkl. aller Null-Commands). Driftet er weit
     * über die Zahl lebender Slots, arbeitet der Pfad an der Slot-Verwaltung statt an der Szene.
     */
    public String descStatsLine() {
        return "GpuCull-Descs: op %d, cut %d, lod %d (Kapazitaet %d, Gates %d)".formatted(
                this.mirrorCount[0], this.mirrorCount[1], this.mirrorCount[DESC_LOD],
                this.descCapacity, this.gateCount);
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
                /* Raster-Rand: das analytische Rect um 1 BASIS-TEXEL erweitern (bei der
                   Viertel-Aufloesungs-Basis ~4-6 Bildpixel) — die Rasterisierung deckt nur
                   Sample-ZENTREN, die exakte Rect-Kante kann im nicht gesampelten
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

                /* zulaessig = lebt und (bei Sections) Sicht-Gate offen. u_LevelFilter:
                   -1 = Sections (ipos.z ist der Gate-Slot), -2 = LOD-Segment (gemergt, kein
                   Filter — ipos.z traegt das Level nur noch informativ). Jeder Slot wird
                   damit pro Phase genau EINMAL besucht. */
                bool zulaessig = d.draw.x != 0u;
                if (zulaessig && u_LevelFilter == -1) {
                    /* Spalte von LOD verdeckt (atomarer Swap wie im CPU-Pfad) */
                    zulaessig = u_Hidden[d.ipos.z] == 0u;
                }

                /* Kamerarelatives AABB: XZ über exakte int-Differenz (Welt-Koordinaten als int,
                   Kamera-Anker als Block + Bruchteil — float-Präzisions-Falle der Roadmap). */
                float camY = float(u_CamBlock.y) + u_CamFrac.y;
                float ox = float(d.ipos.x - u_CamBlock.x) - u_CamFrac.x;
                float oz = float(d.ipos.y - u_CamBlock.z) - u_CamFrac.z;
                /* Offset nur in Phase 0/2 schreiben (Phase 1 teilt den Bereich und liest ihn
                   nicht — die Draws lesen den Phase-0-Stand; der Write war die Haelfte aller
                   Offset-Stores). Phase 0/2 schreiben weiterhin ALLE Slots, auch gecullte —
                   sonst laesen die Draws stale Offsets. */
                if (u_Phase != 1) {
                    /* DrawOffsets.w bleibt ein einzelner float, trägt aber einen exakt
                       darstellbaren Integer: Level (3 Bit), Konfliktmaske (16 Bit) und
                       Positionsskalen-Code (4 Bit). Der Vertex-Shader rekonstruiert daraus
                       exakt 1/1024, 1/127 oder 1/64; der Offset-SSBO bleibt vec4. */
                    uint drawLevel = u_LevelFilter == -1 ? 0u : uint(d.ipos.z) & 7u;
                    uint conflictMask = d.draw.w & 0xFFFFu;
                    uint scaleCode = (d.draw.w >> 16u) & 0xFu;
                    uint metadata = drawLevel | (conflictMask << 3u) | (scaleCode << 19u);
                    u_Offs[uint(u_OffBase) + i] = vec4(ox, float(d.ipos.w) - camY, oz, float(metadata));
                }

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
                } else if (u_Phase == 2) {
                    /* Single-Phase-Fallback (kein Hi-Z verfuegbar: MSAA/erster Frame): alle
                       Frustum-Sichtbaren direkt zeichnen, Vis-Bit fail-open sichtbar setzen —
                       der naechste Two-Phase-Frame startet damit korrekt ueber Phase 0. */
                    if (zulaessig) u_Vis[si] = (gen << 24) | 1u;
                    zeichnen = imFrustum;
                } else {
                    /* Phase 1: Verdeckungstest gegen die Same-Frame-Pyramide. Vis-Write NUR
                       fuer zulaessige Invocations — seit dem LOD-Segment-Merge wird jeder
                       Slot zwar nur noch einmal besucht, der Guard bleibt aber als
                       Defensiv-Regel (Sections: gate-geschlossene Slots schreiben kein
                       Verdikt). visBit=0 NUR bei echtem bestandenem Verdikt, alles andere
                       fail-open sichtbar (Frustum-Rueckkehrer kommen so ueber Phase 0
                       herein, kein Nachzuegler-Burst beim Umschauen). */
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

    /* Basis-Mip der Pyramide: konservatives Footprint-MIN aus dem Szenen-Depth in die
       Pow2-Basis bei ~Viertel-Aufloesung. Jeder Basis-Texel deckt seinen exakten
       Pixel-Bereich ab (lueckenlose Kachelung ueber Ganzzahl-Grenzen) — Reversed-Z-MIN
       ist nie naeher als real, kein Depth-Pixel faellt aus der Reduktion. */
    private static final String HIZ_COPY_SOURCE = """
            #version 460 core
            layout(local_size_x = 16, local_size_y = 16) in;
            uniform sampler2D u_Depth;
            layout(r32f, binding = 0) writeonly uniform image2D u_Dst;
            void main() {
                ivec2 p = ivec2(gl_GlobalInvocationID.xy);
                ivec2 dstSize = imageSize(u_Dst);
                if (p.x >= dstSize.x || p.y >= dstSize.y) return;
                ivec2 srcSize = textureSize(u_Depth, 0);
                int x0 = p.x * srcSize.x / dstSize.x;
                int x1 = (p.x + 1) * srcSize.x / dstSize.x;
                int y0 = p.y * srcSize.y / dstSize.y;
                int y1 = (p.y + 1) * srcSize.y / dstSize.y;
                float d = 1.0;
                for (int y = y0; y < y1; y++) {
                    for (int x = x0; x < x1; x++) {
                        d = min(d, texelFetch(u_Depth, ivec2(x, y), 0).r);
                    }
                }
                imageStore(u_Dst, p, vec4(d));
            }
            """;

    /* Gefaltete Pyramiden-Reduktion: EIN Dispatch schreibt bis zu 4 Mip-Stufen (MIN aus 2x2,
       Reversed-Z: MIN = am weitesten entfernt = konservativ) — eine 8x8-Workgroup reduziert
       ihre 16x16-Quellkachel ueber Shared Memory bis zur 1x1. Ersetzt 13 Einzel-Dispatches
       mit je einer Barrier (Pipeline-Blasen = der FPS-Verlust des GPU-Pfads). Die Basis ist
       Pow2 -> alle Halbierungen exakt; die Klemmung dupliziert nur bei Dimension 1 (exakt
       konservativ), und Out-of-Range-Threads liefern geklemmte Duplikate — fuer die
       Shared-MIN neutral. Der fruehere Odd-Size-Verlust der letzten Spalte/Zeile (Ursache
       der LOD-Loch-Linien: Himmel fiel aus der MIN) ist durch Pow2 strukturell unmoeglich. */
    private static final String HIZ_REDUCE_SOURCE = """
            #version 460 core
            layout(local_size_x = 8, local_size_y = 8) in;
            uniform sampler2D u_Src;
            uniform int u_SrcLevel;
            uniform int u_Levels; // Anzahl zu schreibender Ziel-Level (1..4)
            layout(r32f, binding = 0) writeonly uniform image2D u_Dst0;
            layout(r32f, binding = 1) writeonly uniform image2D u_Dst1;
            layout(r32f, binding = 2) writeonly uniform image2D u_Dst2;
            layout(r32f, binding = 3) writeonly uniform image2D u_Dst3;

            shared float s_Tile[8][8];

            void main() {
                ivec2 lp = ivec2(gl_LocalInvocationID.xy);
                ivec2 p = ivec2(gl_GlobalInvocationID.xy); // Texel in Level u_SrcLevel+1
                ivec2 srcSize = textureSize(u_Src, u_SrcLevel);
                ivec2 s = p * 2;
                float d = texelFetch(u_Src, min(s, srcSize - 1), u_SrcLevel).r;
                d = min(d, texelFetch(u_Src, min(s + ivec2(1, 0), srcSize - 1), u_SrcLevel).r);
                d = min(d, texelFetch(u_Src, min(s + ivec2(0, 1), srcSize - 1), u_SrcLevel).r);
                d = min(d, texelFetch(u_Src, min(s + ivec2(1, 1), srcSize - 1), u_SrcLevel).r);
                ivec2 sz = imageSize(u_Dst0);
                if (p.x < sz.x && p.y < sz.y) imageStore(u_Dst0, p, vec4(d));
                s_Tile[lp.y][lp.x] = d;
                barrier();

                /* Stufen +2..+4 aus Shared Memory (Schrittmuster 2/4/8; barrier() steht
                   IMMER im uniformen Kontrollfluss — nur die Schreiber sind maskiert). */
                if (u_Levels >= 2 && (lp.x & 1) == 0 && (lp.y & 1) == 0) {
                    d = min(min(s_Tile[lp.y][lp.x], s_Tile[lp.y][lp.x + 1]),
                            min(s_Tile[lp.y + 1][lp.x], s_Tile[lp.y + 1][lp.x + 1]));
                    ivec2 q = p >> 1;
                    sz = imageSize(u_Dst1);
                    if (q.x < sz.x && q.y < sz.y) imageStore(u_Dst1, q, vec4(d));
                    s_Tile[lp.y][lp.x] = d;
                }
                barrier();
                if (u_Levels >= 3 && (lp.x & 3) == 0 && (lp.y & 3) == 0) {
                    d = min(min(s_Tile[lp.y][lp.x], s_Tile[lp.y][lp.x + 2]),
                            min(s_Tile[lp.y + 2][lp.x], s_Tile[lp.y + 2][lp.x + 2]));
                    ivec2 q = p >> 2;
                    sz = imageSize(u_Dst2);
                    if (q.x < sz.x && q.y < sz.y) imageStore(u_Dst2, q, vec4(d));
                    s_Tile[lp.y][lp.x] = d;
                }
                barrier();
                if (u_Levels >= 4 && lp.x == 0 && lp.y == 0) {
                    d = min(min(s_Tile[0][0], s_Tile[0][4]), min(s_Tile[4][0], s_Tile[4][4]));
                    ivec2 q = p >> 3;
                    sz = imageSize(u_Dst3);
                    if (q.x < sz.x && q.y < sz.y) imageStore(u_Dst3, q, vec4(d));
                }
            }
            """;
}
