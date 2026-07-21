package de.skyengine.graphics;

import de.skyengine.core.EngineConfig;
import de.skyengine.core.SkyEngine;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL33;

/**
 * Minimaler Frame-Profiler für die Bottleneck-Suche: misst pro Frame die GPU-Zeit der
 * disjunkten Draw-Abschnitte (GL_TIME_ELAPSED) und die CPU-Zeit ausgewählter Sektionen
 * (System.nanoTime). Nur aktiv bei DebugMode.FULL — sonst sind alle Methoden No-ops.
 *
 * GPU-Queries dürfen NICHT verschachteln (GL-Regel für GL_TIME_ELAPSED) — die Hooks
 * liegen deshalb um die disjunkten drawSegment-/Blit-Aufrufe, nicht um ganze Pässe.
 * Query-Ring mit 3 Sets (passend zum Triple-Buffering des ChunkRenderers): gelesen wird
 * ein Set erst bei seiner Wiederverwendung 3 Frames später — dann ist das Ergebnis
 * garantiert verfügbar und glGetQueryObjectui64 stallt nie.
 *
 * Alle Methoden laufen ausschließlich auf dem Render-Thread (GL-Kontext nötig).
 */
public final class FrameProfiler {

    /** Disjunkte GPU-Abschnitte (Reihenfolge = Ausgabe-Reihenfolge). Das LOD-Opaque-Segment
        wird pro LOD-Level einzeln gemessen (Mess-Gate für den Superregionen-Merge) —
        Level ohne sichtbare Regionen zeigen 0. */
    public enum Gpu {
        SOLID("solid"),
        LOD_O_L1("lodO1"),
        LOD_O_L2("lodO2"),
        LOD_O_L3("lodO3"),
        LOD_O_L4("lodO4"),
        LOD_O_L5("lodO5"),
        CUTOUT("cut"),
        TRANSLUCENT("trans"),
        LOD_TRANSLUCENT("lodT"),
        BLIT("blit");

        final String label;

        Gpu(String label) {
            this.label = label;
        }
    }

    /**
     * CPU-Sektionen in Frame-Reihenfolge; FRAME = gesamtes onRender inkl. Swap.
     * Die Statuszeile weist zusätzlich rest = frame − Σ(übrige) aus, damit
     * unattributierte Zeit sofort sichtbar bleibt.
     */
    public enum Cpu {
        TICK("tick"),       // Summe der onUpdate-Aufrufe eines Loop-Durchlaufs (VOR onRender,
                            // also NICHT in FRAME enthalten — bleibt aus der rest-Rechnung raus)
        CLEAR("clear"),     // FBO-Bind + State-Enables + glClear
        ANIM("anim"),       // Textur-Animationen (glTexSubImage-Uploads)
        SYNC("sync"),       // Fence-Wait + Arena-Collect (beginFrame) + Fence-Create (endFrame)
        UPLOAD("upload"),   // Mesh-/LOD-Upload-Queues + Cleanup-Loops
        CULL("cull"),       // Frustum-Tests + Listenbau
        WRITE("write"),     // Command-/Offset-Segmente in den MappedRing
        GLSUB("glsub"),     // GL-Submission: Binds/Uniforms/MDI-Calls beider Chunk-Pässe
        REMESH("remesh"),   // ChunkManager.processRemeshes
        BE("be"),           // BlockEntity-Renderer (iteriert alle Chunks)
        ENT("ent"),         // Entity-Renderer (iteriert alle Chunks)
        SORT("sort"),       // Translucent: Section-Sort + Quad-Sort-Budget
        OVL("ovl"),         // Selection-Box + Crack + Fluid-Overlay
        GUI("gui"),         // GuiManager (HUD inkl. Item-Icons)
        SWAP("swap"),       // glfwSwapBuffers (kann im Treiber blocken)
        FRAME("frame");

        final String label;

        Cpu(String label) {
            this.label = label;
        }
    }

    private static final int SLOTS = 3;

    private static boolean initialized = false;
    private static boolean enabled = false;

    /* Query-Ring: [slot][sektion]; used markiert, ob im Slot-Frame eine Query lief */
    private static final int[][] queries = new int[SLOTS][Gpu.values().length];
    private static final boolean[][] used = new boolean[SLOTS][Gpu.values().length];
    private static long frame = 0;
    private static int slot = 0;

    /* Akkumulatoren seit der letzten Statuszeile (Nanosekunden) */
    private static final long[] gpuSum = new long[Gpu.values().length];
    private static final long[] cpuSum = new long[Cpu.values().length];
    private static final long[] cpuStart = new long[Cpu.values().length];
    private static int gpuFrames = 0;   // Frames mit ausgelesenen GPU-Ergebnissen
    private static int cpuFrames = 0;

    /* Spike-Erfassung: Werte des LAUFENDEN Loop-Durchlaufs (Tick + Frame) — die 1-s-Mittelwerte
       verstecken einzelne Ruckler komplett (ein 40-ms-Frame verschwindet im Schnitt über ~60
       Frames). loopEndSpikeLine() prüft pro Durchlauf gegen den Schwellwert und liefert bei
       Überschreitung EINE Detailzeile mit den Sektionswerten genau dieses Durchlaufs. */
    private static final long SPIKE_THRESHOLD_NANOS = 25_000_000L; // 25 ms
    private static final long[] cpuLoop = new long[Cpu.values().length];
    private static long maxLoopNanos = 0; // schlechtester Durchlauf seit der letzten Statuszeile

    /* GPU-Frame-Spanne: GL_TIMESTAMP-Paar pro Slot (Start in newFrame, Ende vor dem Swap).
       span − Σ(Busy-Sektionen) = Leerlauf-Blasen in der GPU-Timeline des Frames —
       unterscheidet „GPU rechnet wirklich" von „GPU wartet (Present/Latenz)". */
    private static final int[][] tsQueries = new int[SLOTS][2];
    private static final boolean[] tsUsed = new boolean[SLOTS];
    private static long spanSum = 0;
    private static int spanFrames = 0;

    private FrameProfiler() {
    }

    /**
     * Einmal pro Frame am Frame-Anfang aufrufen (Render-Thread): liest die 3 Frames alten
     * Query-Ergebnisse des wiederverwendeten Slots aus und rotiert den Ring.
     */
    public static void newFrame() {
        if (!initialized) {
            initialized = true;
            enabled = SkyEngine.get().getConfig().getDebugMode().equals(EngineConfig.DebugMode.FULL);
            if (enabled) {
                for (int s = 0; s < SLOTS; s++) {
                    for (int q = 0; q < queries[s].length; q++) {
                        queries[s][q] = GL15.glGenQueries();
                    }
                    tsQueries[s][0] = GL15.glGenQueries();
                    tsQueries[s][1] = GL15.glGenQueries();
                }
            }
        }
        if (!enabled) return;

        slot = (int) (frame % SLOTS);
        frame++;

        boolean any = false;
        for (int q = 0; q < queries[slot].length; q++) {
            if (!used[slot][q]) continue;
            gpuSum[q] += GL33.glGetQueryObjectui64(queries[slot][q], GL15.GL_QUERY_RESULT);
            used[slot][q] = false;
            any = true;
        }
        if (any) gpuFrames++;
        cpuFrames++;

        /* Timestamp-Spanne des 3 Frames alten Slots lesen, dann den Start-Stempel
           für diesen Frame setzen (erster GL-Befehl des Frames). */
        if (tsUsed[slot]) {
            long t0 = GL33.glGetQueryObjectui64(tsQueries[slot][0], GL15.GL_QUERY_RESULT);
            long t1 = GL33.glGetQueryObjectui64(tsQueries[slot][1], GL15.GL_QUERY_RESULT);
            spanSum += t1 - t0;
            spanFrames++;
            tsUsed[slot] = false;
        }
        GL33.glQueryCounter(tsQueries[slot][0], GL33.GL_TIMESTAMP);
    }

    /** Direkt vor glfwSwapBuffers aufrufen: setzt den End-Stempel der GPU-Frame-Spanne. */
    public static void gpuFrameEnd() {
        if (!enabled) return;
        GL33.glQueryCounter(tsQueries[slot][1], GL33.GL_TIMESTAMP);
        tsUsed[slot] = true;
    }

    public static boolean isEnabled() {
        return enabled;
    }

    public static void gpuBegin(Gpu section) {
        if (!enabled) return;
        GL15.glBeginQuery(GL33.GL_TIME_ELAPSED, queries[slot][section.ordinal()]);
    }

    public static void gpuEnd(Gpu section) {
        if (!enabled) return;
        GL15.glEndQuery(GL33.GL_TIME_ELAPSED);
        used[slot][section.ordinal()] = true;
    }

    public static void cpuStart(Cpu section) {
        if (!enabled) return;
        cpuStart[section.ordinal()] = System.nanoTime();
    }

    public static void cpuStop(Cpu section) {
        if (!enabled) return;
        long elapsed = System.nanoTime() - cpuStart[section.ordinal()];
        cpuSum[section.ordinal()] += elapsed;
        cpuLoop[section.ordinal()] += elapsed;
    }

    /**
     * Einmal pro Loop-Durchlauf am Ende aufrufen (nach onRender bzw. dem Resize-Skip):
     * prüft Tick+Frame dieses Durchlaufs gegen den Spike-Schwellwert und setzt die
     * Durchlauf-Werte zurück. Liefert bei Überschreitung eine Detailzeile mit den
     * Sektionswerten GENAU DIESES Durchlaufs (µs), sonst null.
     */
    public static String loopEndSpikeLine() {
        if (!enabled) return null;

        long loop = cpuLoop[Cpu.TICK.ordinal()] + cpuLoop[Cpu.FRAME.ordinal()];
        if (loop > maxLoopNanos) maxLoopNanos = loop;

        String line = null;
        if (loop >= SPIKE_THRESHOLD_NANOS) { // µs
            StringBuilder sb = new StringBuilder("SPIKE[us] loop=").append(loop / 1000);
            long attributed = 0;
            for (Cpu c : Cpu.values()) {
                if (c == Cpu.FRAME) continue;
                if (c != Cpu.TICK) attributed += cpuLoop[c.ordinal()]; // TICK liegt außerhalb von FRAME
                sb.append(' ').append(c.label).append('=').append(cpuLoop[c.ordinal()] / 1000);
            }
            long frame = cpuLoop[Cpu.FRAME.ordinal()];
            sb.append(" frame=").append(frame / 1000).append(" rest=").append((frame - attributed) / 1000);
            line = sb.toString();
        }
        java.util.Arrays.fill(cpuLoop, 0L);
        return line;
    }

    /**
     * Baut die 1s-Statuszeile (Durchschnitt in µs pro Frame) und setzt die Akkumulatoren
     * zurück. Gibt null zurück, wenn der Profiler nicht aktiv ist oder noch keine Daten hat.
     */
    public static String statusLineAndReset() {
        if (!enabled || cpuFrames == 0) return null;

        StringBuilder sb = new StringBuilder("GPU[us]"); // µs
        for (Gpu g : Gpu.values()) {
            long avg = gpuFrames > 0 ? gpuSum[g.ordinal()] / gpuFrames / 1000 : 0;
            sb.append(' ').append(g.label).append('=').append(avg);
            gpuSum[g.ordinal()] = 0;
        }
        sb.append(" span=").append(spanFrames > 0 ? spanSum / spanFrames / 1000 : 0);
        spanSum = 0;
        spanFrames = 0;
        sb.append(" | CPU[us]"); // µs
        long attributed = 0;
        for (Cpu c : Cpu.values()) {
            if (c == Cpu.FRAME) continue;
            long avg = cpuSum[c.ordinal()] / cpuFrames / 1000;
            /* TICK läuft VOR onRender und steckt nicht in FRAME → nicht in rest einrechnen */
            if (c != Cpu.TICK) attributed += cpuSum[c.ordinal()];
            sb.append(' ').append(c.label).append('=').append(avg);
            cpuSum[c.ordinal()] = 0;
        }
        long frameAvg = cpuSum[Cpu.FRAME.ordinal()] / cpuFrames / 1000;
        /* rest = im FRAME enthaltene, aber keiner Sektion zugeordnete Zeit */
        long restAvg = (cpuSum[Cpu.FRAME.ordinal()] - attributed) / cpuFrames / 1000;
        sb.append(" | frame=").append(frameAvg).append(" rest=").append(restAvg);
        /* max = schlechtester Loop-Durchlauf (Tick+Frame) der Sekunde — macht Ausreißer
           sichtbar, die der Durchschnitt verschluckt */
        sb.append(" max=").append(maxLoopNanos / 1000);
        maxLoopNanos = 0;
        cpuSum[Cpu.FRAME.ordinal()] = 0;
        gpuFrames = 0;
        cpuFrames = 0;
        return sb.toString();
    }
}
